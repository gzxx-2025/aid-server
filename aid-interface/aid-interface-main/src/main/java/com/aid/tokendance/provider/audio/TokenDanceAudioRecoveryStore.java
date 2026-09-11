package com.aid.tokendance.provider.audio;

import cn.hutool.core.util.StrUtil;
import com.aid.common.config.AidAppConfig;
import com.aid.common.exception.ServiceException;
import com.aid.common.oss.factory.OssFactory;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.tokendance.provider.common.TokenDanceHttpResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * TokenDance 同步音频的私有恢复存储。
 *
 * <p>厂商已返回音频后，先把字节原子写入 Web 不可访问的 {@code profile-private}，再尝试
 * 对象存储。对象存储失败时，同一有效请求只会重试转存，不会再次调用收费的上游接口。
 * Redis 标记和分布式锁用于多节点防重；恢复目录不是共享盘时，其他节点会保守拒绝，绝不
 * 以“本节点看不到文件”为由重新生成。</p>
 */
@Slf4j
@Component
public class TokenDanceAudioRecoveryStore {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final byte[] MAGIC = "AIDTDAR1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_AUDIO_BYTES = 96 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 256 * 1024;
    private static final long MAX_DIRECTORY_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long LOCK_WAIT_SECONDS = 5L;
    /** 未完成恢复文件的本地保留期；到期删除文件后 Redis 阻断标记仍保留，禁止静默重发。 */
    private static final long RETENTION_DAYS = 7L;
    /** 已完成结果只需覆盖现有媒体任务的一小时幂等窗口。 */
    private static final long COMPLETED_MARKER_HOURS = 1L;
    private static final String SUB_PATH = "media-audio-recovery/tokendance";
    private static final String MARKER_PREFIX = "media:tokendance:audio-recovery:";
    private static final String LOCK_SUFFIX = ":lock";
    private static final Set<String> FORMATS = Set.of("mp3", "wav", "pcm", "pcm16", "flac", "ogg_opus");
    private static final TypeReference<Map<String, Object>> USAGE_TYPE = new TypeReference<>() { };

    private final RedissonClient redissonClient;
    private final String instanceId = UUID.randomUUID().toString();

    public TokenDanceAudioRecoveryStore(RedissonClient redissonClient) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
    }

    /** 已生成音频及其真实用量。字节只存在于内存和私有恢复文件，不进入任务表。 */
    public record GeneratedAudio(byte[] bytes, String format, Long durationMs,
                                 Map<String, Object> usage, String rawResponse,
                                 String directText) {
        public GeneratedAudio {
            usage = usage == null ? Map.of() : Map.copyOf(usage);
        }
    }

    /** 明确的上游拒绝不会留下“不确定已提交”标记，可由正常业务规则决定是否再次请求。 */
    public record GenerationResult(GeneratedAudio audio, ProviderSubmitResult definitiveFailure) {
        public static GenerationResult success(GeneratedAudio audio) {
            return new GenerationResult(Objects.requireNonNull(audio, "audio"), null);
        }

        public static GenerationResult failure(ProviderSubmitResult failure) {
            return new GenerationResult(null, Objects.requireNonNull(failure, "failure"));
        }
    }

    @FunctionalInterface
    public interface Generator {
        GenerationResult generate() throws Exception;
    }

    /** 这些 HTTP 状态明确表示本次请求被拒绝；超时和服务端异常都可能已经开始生成。 */
    static boolean isDefinitiveHttpRejection(TokenDanceHttpResponse response) {
        if (response == null) return false;
        return Set.of(400, 401, 403, 404, 422, 429).contains(response.getStatusCode());
    }

    /**
     * 在分布式防重边界内恢复或生成音频。任何无法确认“请求未送达上游”的异常都会保留
     * UNCERTAIN/SUBMITTING 标记，后续相同请求 fail closed，需人工核对后处理。
     */
    public ProviderSubmitResult recoverOrGenerate(AiModelConfigVo config,
                                                   MediaAudioGenerateRequest request,
                                                   byte[] trustedSample,
                                                   Generator generator) {
        String key = recoveryKey(config, request, trustedSample);
        RLock lock = redissonClient.getLock(MARKER_PREFIX + key + LOCK_SUFFIX);
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                throw pending("相同配音请求正在处理中，请稍后重试转存");
            }
            Path directory = prepareDirectory();
            RBucket<String> markerBucket = redissonClient.getBucket(MARKER_PREFIX + key);
            Marker marker = readMarker(markerBucket.get());
            ProviderSubmitResult recovered = recoverExisting(key, directory, marker, markerBucket);
            if (recovered != null) {
                return recovered;
            }
            // 先持久化“可能已提交”状态。进程在网络调用中崩溃时，后续请求不会隐式重发。
            writeMarker(markerBucket, Marker.submitting(instanceId));
            GenerationResult generated;
            try {
                generated = generator.generate();
            } catch (Exception exception) {
                writeMarker(markerBucket, Marker.uncertain(instanceId));
                throw exception;
            }
            if (generated == null) {
                writeMarker(markerBucket, Marker.uncertain(instanceId));
                throw pending("配音提交结果待人工确认，已阻止自动重发");
            }
            if (generated.definitiveFailure() != null) {
                markerBucket.delete();
                return generated.definitiveFailure();
            }
            GeneratedAudio audio;
            try {
                audio = validate(generated.audio());
            } catch (Exception exception) {
                clear(generated.audio());
                throw exception;
            }
            Path artifact = artifactPath(directory, key);
            try {
                writeArtifactAtomically(artifact, audio);
                writeMarker(markerBucket, Marker.staged(instanceId));
            } catch (Exception exception) {
                clear(audio);
                try {
                    writeMarker(markerBucket, Marker.uncertain(instanceId));
                } catch (Exception ignored) {
                    // 已有 SUBMITTING 标记时保留它；两种状态都会阻止再次生成。
                }
                throw pending("配音已提交但本地恢复文件写入失败，已阻止再次生成");
            }
            return uploadStaged(artifact, audio, markerBucket);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw pending("配音恢复锁等待中断，请稍后重试");
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw pending("配音结果待确认，已阻止自动重发");
        } finally {
            if (locked) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (Exception exception) {
                    log.warn("TokenDance 音频恢复锁释放失败, keyPrefix={}, errorType={}",
                            key.substring(0, 12), exception.getClass().getSimpleName());
                }
            }
        }
    }

    private ProviderSubmitResult recoverExisting(String key, Path directory, Marker marker,
                                                   RBucket<String> markerBucket) throws IOException {
        Path artifact = artifactPath(directory, key);
        if (marker != null && "COMPLETED".equals(marker.state)) {
            return marker.toSubmitResult();
        }
        if (Files.isRegularFile(artifact)) {
            GeneratedAudio audio = readArtifact(artifact);
            try {
                writeMarker(markerBucket, Marker.staged(
                        marker == null ? instanceId : StrUtil.blankToDefault(marker.owner, instanceId)));
            } catch (Exception exception) {
                clear(audio);
                throw exception;
            }
            return uploadStaged(artifact, audio, markerBucket);
        }
        if (marker == null) {
            return null;
        }
        if ("STAGED".equals(marker.state)) {
            throw pending("配音恢复文件位于其他节点或已损坏，请在原节点检查转存，已阻止再次生成");
        }
        throw pending("配音上游提交状态待人工确认，已阻止再次生成");
    }

    private ProviderSubmitResult uploadStaged(Path artifact, GeneratedAudio audio,
                                               RBucket<String> markerBucket) {
        try {
            String url = OssFactory.instance().uploadSuffix(audio.bytes(), suffix(audio.format()),
                    contentType(audio.format())).getUrl();
            if (StrUtil.isBlank(url)) {
                throw new IOException("empty upload url");
            }
            ProviderSubmitResult result = toSubmitResult(audio, url);
            writeMarker(markerBucket, Marker.completed(instanceId, result));
            Files.deleteIfExists(artifact);
            return result;
        } catch (Exception exception) {
            // STAGED 保留真实 usage 和音频文件；重试只执行此上传分支。
            throw pending("配音已生成，转存失败；重试将仅恢复转存，不会再次生成");
        } finally {
            Arrays.fill(audio.bytes(), (byte) 0);
        }
    }

    private ProviderSubmitResult toSubmitResult(GeneratedAudio audio, String url) {
        return ProviderSubmitResult.builder()
                .ossUrl(url)
                .audioDurationMs(audio.durationMs())
                .usage(audio.usage())
                .rawResponse(audio.rawResponse())
                .directText(audio.directText())
                .build();
    }

    private GeneratedAudio validate(GeneratedAudio audio) {
        if (audio == null || audio.bytes() == null || audio.bytes().length == 0
                || audio.bytes().length > MAX_AUDIO_BYTES) {
            throw new ServiceException("音频响应无效");
        }
        String format = normalizeFormat(audio.format());
        String raw = StrUtil.subWithLength(audio.rawResponse(), 0, MAX_METADATA_BYTES / 2);
        String text = StrUtil.subWithLength(audio.directText(), 0, 16_384);
        return new GeneratedAudio(audio.bytes(), format, audio.durationMs(), audio.usage(), raw, text);
    }

    private String recoveryKey(AiModelConfigVo config, MediaAudioGenerateRequest request,
                               byte[] trustedSample) {
        if (config == null || request == null || request.getUserId() == null
                || request.getUserId() == 0L || config.getProviderId() == null
                || config.getProviderId() <= 0L || config.getCredentialVersion() == null
                || config.getCredentialVersion() <= 0 || StrUtil.isBlank(config.getProtocol())) {
            throw new ServiceException("配音恢复身份或凭证版本缺失");
        }
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("ownerId", request.getUserId());
            root.put("providerId", config.getProviderId());
            root.put("credentialVersion", config.getCredentialVersion());
            root.put("protocol", config.getProtocol().trim());
            root.put("realModelCode", StrUtil.blankToDefault(config.getRealModelCode(), config.getModelCode()));
            JsonNode requestNode = MAPPER.valueToTree(request);
            // 参考样本 URL 会在真正提交前被签名，query 每次变化；内容摘要才是稳定且可信的身份。
            if (requestNode instanceof ObjectNode requestObject
                    && requestObject.path("options") instanceof ObjectNode options) {
                options.remove("referenceSampleUrl");
            }
            root.set("request", requestNode);
            root.put("trustedSampleSha256", trustedSample == null ? "" : sha256(trustedSample));
            return sha256(MAPPER.writeValueAsBytes(root));
        } catch (Exception exception) {
            throw new ServiceException("配音恢复键生成失败");
        }
    }

    private Path prepareDirectory() throws IOException {
        String configuredProfile = AidAppConfig.getProfile();
        if (StrUtil.isBlank(configuredProfile)) {
            throw new IOException("profile missing");
        }
        Path profile = Paths.get(configuredProfile).toAbsolutePath().normalize();
        Path profileName = profile.getFileName();
        if (profileName == null) {
            throw new IOException("profile invalid");
        }
        Path privateRoot = profile.resolveSibling(profileName + "-private").normalize();
        Path directory = privateRoot.resolve(SUB_PATH).normalize();
        if (!directory.startsWith(privateRoot)) {
            throw new IOException("recovery directory invalid");
        }
        Files.createDirectories(directory);
        restrictDirectory(directory);
        cleanupExpired(directory);
        long size;
        try (var paths = Files.list(directory)) {
            size = paths.filter(path -> path.getFileName().toString().endsWith(".recovery"))
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try { return Files.size(path); } catch (IOException ignored) { return 0L; }
                    }).sum();
        }
        if (size > MAX_DIRECTORY_BYTES) {
            throw new IOException("recovery directory full");
        }
        return directory;
    }

    private void cleanupExpired(Path directory) throws IOException {
        Instant cutoff = Instant.now().minus(Duration.ofDays(RETENTION_DAYS));
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if ((!name.endsWith(".recovery") && !name.endsWith(".tmp"))
                        || !Files.isRegularFile(path)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException exception) {
                    log.warn("TokenDance 过期音频恢复文件清理失败, file={}, errorType={}",
                            name, exception.getClass().getSimpleName());
                }
            }
        }
    }

    private Path artifactPath(Path directory, String key) throws IOException {
        if (key == null || !key.matches("[0-9a-f]{64}")) {
            throw new IOException("recovery key invalid");
        }
        Path artifact = directory.resolve(key + ".recovery").normalize();
        if (!artifact.startsWith(directory)) {
            throw new IOException("recovery path invalid");
        }
        return artifact;
    }

    private void writeArtifactAtomically(Path artifact, GeneratedAudio audio) throws IOException {
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("createdAt", Instant.now().toString());
        metadata.put("format", audio.format());
        if (audio.durationMs() == null) metadata.putNull("durationMs");
        else metadata.put("durationMs", audio.durationMs());
        metadata.set("usage", MAPPER.valueToTree(audio.usage()));
        if (audio.rawResponse() == null) metadata.putNull("rawResponse");
        else metadata.put("rawResponse", audio.rawResponse());
        if (audio.directText() == null) metadata.putNull("directText");
        else metadata.put("directText", audio.directText());
        metadata.put("audioSha256", sha256(audio.bytes()));
        byte[] header = MAPPER.writeValueAsBytes(metadata);
        if (header.length > MAX_METADATA_BYTES) {
            throw new IOException("recovery metadata too large");
        }
        Path temporary = artifact.resolveSibling(artifact.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)))) {
                output.write(MAGIC);
                output.writeInt(header.length);
                output.write(header);
                output.writeInt(audio.bytes().length);
                output.write(audio.bytes());
            }
            restrictFile(temporary);
            try {
                Files.move(temporary, artifact, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, artifact, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictFile(artifact);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private GeneratedAudio readArtifact(Path artifact) throws IOException {
        long size = Files.size(artifact);
        if (size <= MAGIC.length + 8L || size > MAX_AUDIO_BYTES + MAX_METADATA_BYTES + 16L) {
            throw new IOException("recovery artifact size invalid");
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(artifact)))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!MessageDigest.isEqual(MAGIC, magic)) throw new IOException("recovery artifact magic invalid");
            int headerLength = input.readInt();
            if (headerLength <= 0 || headerLength > MAX_METADATA_BYTES) throw new IOException("recovery header invalid");
            JsonNode metadata = MAPPER.readTree(input.readNBytes(headerLength));
            int audioLength = input.readInt();
            if (audioLength <= 0 || audioLength > MAX_AUDIO_BYTES) throw new IOException("recovery audio size invalid");
            byte[] audio = input.readNBytes(audioLength);
            if (audio.length != audioLength || input.read() != -1
                    || !MessageDigest.isEqual(sha256(audio).getBytes(StandardCharsets.US_ASCII),
                    metadata.path("audioSha256").asText("").getBytes(StandardCharsets.US_ASCII))) {
                Arrays.fill(audio, (byte) 0);
                throw new IOException("recovery audio checksum invalid");
            }
            Map<String, Object> usage = metadata.path("usage").isObject()
                    ? MAPPER.convertValue(metadata.path("usage"), USAGE_TYPE) : Map.of();
            return new GeneratedAudio(audio, normalizeFormat(metadata.path("format").asText()),
                    metadata.path("durationMs").isIntegralNumber() ? metadata.path("durationMs").longValue() : null,
                    usage, textOrNull(metadata, "rawResponse"), textOrNull(metadata, "directText"));
        }
    }

    private Marker readMarker(String json) {
        if (StrUtil.isBlank(json)) return null;
        try {
            JsonNode node = MAPPER.readTree(json);
            return new Marker(node.path("state").asText(), node.path("owner").asText(null),
                    node.path("ossUrl").asText(null),
                    node.path("durationMs").isIntegralNumber() ? node.path("durationMs").longValue() : null,
                    node.path("usage").isObject() ? MAPPER.convertValue(node.path("usage"), USAGE_TYPE) : Map.of(),
                    node.path("rawResponse").asText(null), node.path("directText").asText(null));
        } catch (Exception exception) {
            return new Marker("UNCERTAIN", null, null, null, Map.of(), null, null);
        }
    }

    private void writeMarker(RBucket<String> bucket, Marker marker) {
        try {
            String json = MAPPER.writeValueAsString(marker.toJson());
            if ("COMPLETED".equals(marker.state)) {
                bucket.set(json, COMPLETED_MARKER_HOURS, TimeUnit.HOURS);
            } else {
                // SUBMITTING/UNCERTAIN/STAGED 只能由明确的恢复或人工核对清除。即使本地文件
                // 因保留期到期被清理，也继续 fail closed，绝不在 TTL 后静默再次收费生成。
                bucket.set(json);
            }
        } catch (Exception exception) {
            throw pending("配音恢复状态持久化失败，已阻止调用上游");
        }
    }

    private record Marker(String state, String owner, String ossUrl, Long durationMs,
                          Map<String, Object> usage, String rawResponse, String directText) {
        static Marker submitting(String owner) { return new Marker("SUBMITTING", owner, null, null, Map.of(), null, null); }
        static Marker uncertain(String owner) { return new Marker("UNCERTAIN", owner, null, null, Map.of(), null, null); }
        static Marker staged(String owner) { return new Marker("STAGED", owner, null, null, Map.of(), null, null); }
        static Marker completed(String owner, ProviderSubmitResult result) {
            return new Marker("COMPLETED", owner, result.getOssUrl(), result.getAudioDurationMs(),
                    result.getUsage() == null ? Map.of() : result.getUsage(), result.getRawResponse(), result.getDirectText());
        }
        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("state", state);
            node.put("owner", owner);
            if (ossUrl != null) node.put("ossUrl", ossUrl);
            if (durationMs != null) node.put("durationMs", durationMs);
            node.set("usage", MAPPER.valueToTree(usage == null ? Map.of() : usage));
            if (rawResponse != null) node.put("rawResponse", rawResponse);
            if (directText != null) node.put("directText", directText);
            return node;
        }
        ProviderSubmitResult toSubmitResult() {
            if (StrUtil.isBlank(ossUrl)) throw pending("配音恢复完成标记无效，已阻止再次生成");
            return ProviderSubmitResult.builder().ossUrl(ossUrl).audioDurationMs(durationMs)
                    .usage(usage).rawResponse(rawResponse).directText(directText).build();
        }
    }

    private static String normalizeFormat(String value) {
        String format = StrUtil.blankToDefault(value, "mp3").trim().toLowerCase();
        if (!FORMATS.contains(format)) throw new ServiceException("音频格式无效");
        return format;
    }

    private static String suffix(String format) {
        return switch (format) {
            case "ogg_opus" -> ".ogg";
            case "pcm16" -> ".pcm";
            default -> "." + format;
        };
    }

    private static String contentType(String format) {
        return switch (format) {
            case "wav" -> "audio/wav";
            case "pcm", "pcm16" -> "audio/pcm";
            case "flac" -> "audio/flac";
            case "ogg_opus" -> "audio/ogg";
            default -> "audio/mpeg";
        };
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void clear(GeneratedAudio audio) {
        if (audio != null && audio.bytes() != null) {
            Arrays.fill(audio.bytes(), (byte) 0);
        }
    }

    private static ServiceException pending(String message) {
        return new ServiceException(message);
    }

    private static void restrictDirectory(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 使用目录既有 ACL；目录位于 profile 同级的 private 根，不在 Web 静态映射下。
        }
    }

    private static void restrictFile(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 使用目录既有 ACL。
        }
    }
}
