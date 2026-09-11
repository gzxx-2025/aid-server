package com.aid.tokendance.catalog;

import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.upgrade.constant.UpgradeConfigKeys;
import com.aid.upgrade.util.ManifestSignatureVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** 负责 TokenDance 在线目录的可信拉取、缓存和内置回退。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TokenDanceCatalogManager {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COST_RESOURCE = "tokendance/catalog/cost-snapshot.json";
    private static final String CAPABILITY_RESOURCE = "tokendance/catalog/verified-capabilities.json";
    private static final String SOURCE_RESOURCE = "tokendance/catalog/official-source-index.json";
    private static final String VIDEO_RESOURCE = "tokendance/catalog/verified-video-token-estimates.json";
    private static final String COMPATIBILITY_RESOURCE = "tokendance/catalog/compatibility.json";
    private static final String CATALOG_ID = "tokendance";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final int SUPPORTED_CAPABILITY_CONTRACT = 1;
    private static final int SUPPORTED_BILLING_CONTRACT = 1;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_BUNDLE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final long REFRESH_TTL_MS = 6 * 60 * 60_000L;
    private static final long ERROR_TTL_MS = 5 * 60_000L;
    private static final Set<String> TRUSTED_HOSTS = Set.of("raw.giteeusercontent.com");
    private static final Set<String> TRUSTED_HOST_SUFFIXES = Set.of(
            "gitee.com", "github.com", "githubusercontent.com");
    private static final List<String> DEFAULT_CATALOG_MANIFEST_URLS = List.of(
            "https://gitee.com/gzxx-2025/aid-server/raw/master/model-catalog/tokendance/latest.json",
            "https://raw.githubusercontent.com/gzxx-2025/aid-server/master/model-catalog/tokendance/latest.json");

    private final ConfigService configService;
    private final AtomicReference<TokenDanceCatalogSnapshot> active = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<TokenDanceCatalogSnapshot>> inFlight = new AtomicReference<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Value("${aid.profile:D:/aid/uploadPath}")
    private String profile;

    @Value("${aid.upgrade.current-version:1.0.0}")
    private String currentVersion;

    @Value("${aid.upgrade.manifest-public-key:}")
    private String manifestPublicKey;

    private volatile long lastCheckedAtMs;
    private volatile String lastCheckedAt;
    private volatile String lastError;
    private volatile String lastManifestUrl;

    @PostConstruct
    public void initialize() {
        TokenDanceCatalogSnapshot bundled = buildSnapshot(buildBundledBundle(), "BUNDLED", null);
        active.set(bundled);
        TokenDanceCatalogSnapshot cached = loadCached();
        if (cached != null && !isOlder(cached, bundled)) active.set(cached);
    }

    TokenDanceCatalogSnapshot current(boolean refreshIfDue) {
        if (refreshIfDue && System.currentTimeMillis() - lastCheckedAtMs >= effectiveTtl()) {
            return refresh(false);
        }
        return active.get();
    }

    public TokenDanceCatalogSnapshot refresh(boolean force) {
        if (!force && System.currentTimeMillis() - lastCheckedAtMs < effectiveTtl()) return active.get();
        CompletableFuture<TokenDanceCatalogSnapshot> created = new CompletableFuture<>();
        CompletableFuture<TokenDanceCatalogSnapshot> pending = inFlight.compareAndExchange(null, created);
        if (pending != null) return pending.join();
        try {
            TokenDanceCatalogSnapshot refreshed = refreshInternal();
            created.complete(refreshed);
            return refreshed;
        } catch (RuntimeException exception) {
            created.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.compareAndSet(created, null);
        }
    }

    public JsonNode status() {
        TokenDanceCatalogSnapshot snapshot = active.get();
        ObjectNode result = MAPPER.createObjectNode();
        result.put("catalogId", CATALOG_ID);
        result.put("catalogVersion", snapshot.catalogVersion());
        result.put("publishedAt", snapshot.publishedAt());
        result.put("loadedAt", snapshot.loadedAt().toString());
        result.put("source", snapshot.source());
        if (StrUtil.isNotBlank(snapshot.sourceUrl())) result.put("sourceUrl", snapshot.sourceUrl());
        result.put("currentAppVersion", currentVersion);
        result.put("minimumAppVersion", snapshot.minimumAppVersion());
        result.put("modelCount", snapshot.entries().size());
        result.put("stale", !"REMOTE".equals(snapshot.source()) || StrUtil.isNotBlank(lastError));
        if (StrUtil.isNotBlank(lastCheckedAt)) result.put("checkedAt", lastCheckedAt);
        if (StrUtil.isNotBlank(lastManifestUrl)) result.put("manifestUrl", lastManifestUrl);
        if (StrUtil.isNotBlank(lastError)) result.put("checkError", lastError);
        return result;
    }

    public String currentVersion() { return currentVersion; }
    int supportedCapabilityContract() { return SUPPORTED_CAPABILITY_CONTRACT; }
    int supportedBillingContract() { return SUPPORTED_BILLING_CONTRACT; }

    private TokenDanceCatalogSnapshot refreshInternal() {
        lastCheckedAtMs = System.currentTimeMillis();
        lastCheckedAt = Instant.now().toString();
        List<String> manifestUrls = resolveCatalogManifestUrls();
        List<String> failures = new ArrayList<>();
        for (String manifestUrl : manifestUrls) {
            try {
                byte[] manifestBytes = fetch(manifestUrl, MAX_MANIFEST_BYTES);
                String manifestText = new String(manifestBytes, StandardCharsets.UTF_8);
                if (!ManifestSignatureVerifier.verify(manifestText, manifestPublicKey)) {
                    throw new IllegalStateException("目录签名无效");
                }
                JsonNode manifest = MAPPER.readTree(manifestBytes);
                validateManifest(manifest);
                List<String> bundleUrls = readBundleUrls(manifest);
                Exception lastBundleError = null;
                for (String bundleUrl : bundleUrls) {
                    try {
                        byte[] bundleBytes = fetch(bundleUrl, MAX_BUNDLE_BYTES);
                        verifyBundle(manifest, bundleBytes);
                        TokenDanceCatalogSnapshot candidate = buildSnapshot(
                                MAPPER.readTree(bundleBytes), "REMOTE", bundleUrl);
                        if (!Objects.equals(candidate.catalogVersion(), manifest.path("catalogVersion").asText())
                                || !Objects.equals(candidate.minimumAppVersion(), manifest.path("minimumAppVersion").asText())
                                || !Objects.equals(candidate.publishedAt(), manifest.path("publishedAt").asText())) {
                            throw new IllegalStateException("目录清单不匹配");
                        }
                        if (isOlder(candidate, active.get())) throw new IllegalStateException("目录版本回退");
                        active.set(candidate);
                        persistCache(manifestBytes, bundleBytes);
                        lastManifestUrl = manifestUrl;
                        lastError = null;
                        return candidate;
                    } catch (Exception exception) {
                        lastBundleError = exception;
                    }
                }
                throw lastBundleError == null ? new IllegalStateException("目录地址为空") : lastBundleError;
            } catch (Exception exception) {
                failures.add(safeFailure(exception));
                log.warn("TokenDance 在线目录刷新失败: url={}, reason={}", manifestUrl, safeFailure(exception));
            }
        }
        lastError = failures.isEmpty() ? "在线目录不可用" : failures.get(0);
        return active.get();
    }

    private List<String> resolveCatalogManifestUrls() {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        try {
            Map<String, String> config = configService.getConfigValues(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE);
            String releaseUrl = StrUtil.blankToDefault(
                    config.get(UpgradeConfigKeys.KEY_MANIFEST_URL), UpgradeConfigKeys.DEFAULT_MANIFEST_URL);
            byte[] releaseBytes = fetch(releaseUrl, MAX_MANIFEST_BYTES);
            String releaseText = new String(releaseBytes, StandardCharsets.UTF_8);
            if (ManifestSignatureVerifier.verify(releaseText, manifestPublicKey)) {
                JsonNode manifest = MAPPER.readTree(releaseBytes);
                JsonNode configured = manifest.path("modelCatalog").path("manifestUrls");
                if (configured.isArray()) configured.forEach(value -> {
                    if (value.isTextual() && isTrustedHttps(value.asText())) urls.add(value.asText());
                });
            }
        } catch (Exception exception) {
            log.debug("未从应用升级清单取得模型目录地址: {}", safeFailure(exception));
        }
        urls.addAll(DEFAULT_CATALOG_MANIFEST_URLS);
        return List.copyOf(urls);
    }

    private TokenDanceCatalogSnapshot loadCached() {
        Path cacheDir = cacheDirectory();
        TokenDanceCatalogSnapshot current = loadCachedPair(
                cacheDir.resolve("current-manifest.json"), cacheDir.resolve("current-bundle.json"));
        if (current != null) return current;
        return loadCachedPair(
                cacheDir.resolve("previous-manifest.json"), cacheDir.resolve("previous-bundle.json"));
    }

    private TokenDanceCatalogSnapshot loadCachedPair(Path manifestPath, Path bundlePath) {
        if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(bundlePath)) return null;
        try {
            byte[] manifestBytes = readBounded(manifestPath, MAX_MANIFEST_BYTES);
            String manifestText = new String(manifestBytes, StandardCharsets.UTF_8);
            if (!ManifestSignatureVerifier.verify(manifestText, manifestPublicKey)) return null;
            JsonNode manifest = MAPPER.readTree(manifestBytes);
            validateManifest(manifest);
            byte[] bundleBytes = readBounded(bundlePath, MAX_BUNDLE_BYTES);
            verifyBundle(manifest, bundleBytes);
            TokenDanceCatalogSnapshot snapshot = buildSnapshot(MAPPER.readTree(bundleBytes), "CACHE", null);
            if (!Objects.equals(snapshot.catalogVersion(), manifest.path("catalogVersion").asText())
                    || !Objects.equals(snapshot.minimumAppVersion(), manifest.path("minimumAppVersion").asText())
                    || !Objects.equals(snapshot.publishedAt(), manifest.path("publishedAt").asText())) {
                throw new IllegalStateException("目录缓存不匹配");
            }
            return snapshot;
        } catch (Exception exception) {
            log.warn("TokenDance 目录缓存副本无效，已忽略: {}", safeFailure(exception));
            return null;
        }
    }

    private void persistCache(byte[] manifestBytes, byte[] bundleBytes) {
        try {
            Path directory = cacheDirectory();
            Files.createDirectories(directory);
            Path currentManifest = directory.resolve("current-manifest.json");
            Path currentBundle = directory.resolve("current-bundle.json");
            if (Files.isRegularFile(currentManifest)) {
                Files.copy(currentManifest, directory.resolve("previous-manifest.json"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.isRegularFile(currentBundle)) {
                Files.copy(currentBundle, directory.resolve("previous-bundle.json"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            atomicWrite(currentManifest, manifestBytes);
            atomicWrite(currentBundle, bundleBytes);
        } catch (IOException exception) {
            log.warn("TokenDance 目录缓存写入失败: {}", safeFailure(exception));
        }
    }

    private void atomicWrite(Path target, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private TokenDanceCatalogSnapshot buildSnapshot(JsonNode root, String source, String sourceUrl) {
        if (root == null || !root.isObject()
                || root.path("schemaVersion").asInt() != SUPPORTED_SCHEMA_VERSION
                || !CATALOG_ID.equals(root.path("catalogId").asText())) {
            throw new IllegalStateException("目录格式无效");
        }
        String catalogVersion = requiredText(root, "catalogVersion");
        String publishedAt = requiredText(root, "publishedAt");
        try { Instant.parse(publishedAt); }
        catch (DateTimeParseException exception) { throw new IllegalStateException("目录时间无效", exception); }
        String minimumVersion = requiredText(root, "minimumAppVersion");
        JsonNode costRoot = requiredObject(root, "costSnapshot");
        JsonNode capabilityRoot = requiredObject(root, "verifiedCapabilities");
        JsonNode sourceRoot = requiredObject(root, "officialSourceIndex");
        JsonNode videoRoot = requiredObject(root, "verifiedVideoTokenEstimates");
        if (!"model_call_cost".equals(costRoot.path("priceType").asText())
                || !"CNY".equals(costRoot.path("currency").asText())
                || !costRoot.path("models").isArray()) {
            throw new IllegalStateException("成本目录无效");
        }
        LinkedHashMap<String, JsonNode> entries = new LinkedHashMap<>();
        for (JsonNode model : costRoot.path("models")) {
            String modelId = requiredText(model, "modelId");
            if (entries.putIfAbsent(modelId, model) != null) throw new IllegalStateException("目录模型重复");
        }
        TokenDanceCapabilityTemplateRegistry registry =
                new TokenDanceCapabilityTemplateRegistry(costRoot, capabilityRoot, sourceRoot);
        TokenDanceCatalogPricingCompiler.PricingContext pricingContext =
                TokenDanceCatalogPricingCompiler.createContext(videoRoot);
        return new TokenDanceCatalogSnapshot(
                catalogVersion,
                publishedAt,
                minimumVersion,
                source,
                sourceUrl,
                Instant.now(),
                root,
                Collections.unmodifiableMap(entries),
                registry,
                pricingContext);
    }

    private JsonNode buildBundledBundle() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SUPPORTED_SCHEMA_VERSION);
        root.put("catalogId", CATALOG_ID);
        JsonNode cost = readResource(COST_RESOURCE);
        JsonNode compatibility = readResource(COMPATIBILITY_RESOURCE);
        String snapshotDate = cost.path("snapshotDate").asText("bundled").replace("-", ".");
        root.put("catalogVersion", snapshotDate + ".1");
        root.put("publishedAt", cost.path("models").path(0).path("fetchedAt").asText(Instant.EPOCH.toString()));
        root.put("minimumAppVersion", compatibility.path("minimumAppVersion").asText(currentVersion));
        root.put("capabilityContractVersion", compatibility.path("capabilityContractVersion").asInt(1));
        root.put("billingContractVersion", compatibility.path("billingContractVersion").asInt(1));
        root.set("compatibility", compatibility);
        root.set("aidCompatibilityPolicy", TokenDanceCompatibilityPolicy.bundled());
        root.set("costSnapshot", cost);
        root.set("verifiedCapabilities", readResource(CAPABILITY_RESOURCE));
        root.set("officialSourceIndex", readResource(SOURCE_RESOURCE));
        root.set("verifiedVideoTokenEstimates", readResource(VIDEO_RESOURCE));
        return root;
    }

    private void validateManifest(JsonNode manifest) {
        if (manifest == null || !manifest.isObject()
                || manifest.path("schemaVersion").asInt() != SUPPORTED_SCHEMA_VERSION
                || !CATALOG_ID.equals(manifest.path("catalogId").asText())) {
            throw new IllegalStateException("目录清单无效");
        }
        requiredText(manifest, "catalogVersion");
        requiredText(manifest, "publishedAt");
        requiredText(manifest, "minimumAppVersion");
        JsonNode bundle = requiredObject(manifest, "bundle");
        requiredText(bundle, "url");
        String sha256 = requiredText(bundle, "sha256");
        if (!sha256.matches("[0-9a-fA-F]{64}") || bundle.path("size").asLong() <= 0
                || bundle.path("size").asLong() > MAX_BUNDLE_BYTES) {
            throw new IllegalStateException("目录摘要无效");
        }
    }

    private List<String> readBundleUrls(JsonNode manifest) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        JsonNode bundle = manifest.path("bundle");
        if (isTrustedHttps(bundle.path("url").asText())) urls.add(bundle.path("url").asText());
        JsonNode mirrors = bundle.path("mirrors");
        if (mirrors.isArray()) mirrors.forEach(value -> {
            if (value.isTextual() && isTrustedHttps(value.asText())) urls.add(value.asText());
        });
        if (urls.isEmpty()) throw new IllegalStateException("目录地址无效");
        return List.copyOf(urls);
    }

    private void verifyBundle(JsonNode manifest, byte[] bytes) {
        long expectedSize = manifest.path("bundle").path("size").asLong();
        if (bytes.length != expectedSize) throw new IllegalStateException("目录大小不匹配");
        String expected = manifest.path("bundle").path("sha256").asText();
        if (!MessageDigest.isEqual(expected.toLowerCase().getBytes(StandardCharsets.US_ASCII),
                sha256(bytes).getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("目录摘要不匹配");
        }
    }

    private byte[] fetch(String url, int maxBytes) throws IOException, InterruptedException {
        URI current = URI.create(url);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            if (!isTrustedHttps(current.toString())) throw new IOException("目录地址不可信");
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                response.body().close();
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new IOException("重定向地址缺失"));
                current = current.resolve(location);
                continue;
            }
            if (status != 200) {
                response.body().close();
                throw new IOException("在线目录响应异常");
            }
            try (InputStream stream = response.body()) {
                return readBounded(stream, maxBytes);
            }
        }
        throw new IOException("在线目录重定向过多");
    }

    private boolean isTrustedHttps(String url) {
        try {
            URI uri = URI.create(url);
            if (!Objects.equals("https", uri.getScheme()) || StrUtil.isBlank(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1) return false;
            String host = uri.getHost().toLowerCase();
            return TRUSTED_HOSTS.contains(host) || TRUSTED_HOST_SUFFIXES.stream()
                    .anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] readBounded(Path path, int maxBytes) throws IOException {
        if (Files.size(path) > maxBytes) throw new IOException("目录文件过大");
        try (InputStream stream = Files.newInputStream(path)) { return readBounded(stream, maxBytes); }
    }

    private byte[] readBounded(InputStream stream, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) throw new IOException("目录文件过大");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private Path cacheDirectory() {
        return Path.of(profile).toAbsolutePath().normalize().resolve("model-catalog").resolve(CATALOG_ID);
    }

    private JsonNode readResource(String path) {
        try (InputStream stream = new ClassPathResource(path).getInputStream()) {
            JsonNode root = MAPPER.readTree(stream);
            if (root == null || !root.isObject()) throw new IllegalStateException("内置目录无效");
            return root;
        } catch (IOException exception) {
            throw new IllegalStateException("内置目录无效", exception);
        }
    }

    private boolean isOlder(TokenDanceCatalogSnapshot candidate, TokenDanceCatalogSnapshot baseline) {
        try { return Instant.parse(candidate.publishedAt()).isBefore(Instant.parse(baseline.publishedAt())); }
        catch (DateTimeParseException exception) { return true; }
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException("目录摘要失败", exception); }
    }

    private String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException("目录字段缺失");
        return value.asText();
    }

    private JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw new IllegalStateException("目录字段无效");
        return value;
    }

    private String safeFailure(Exception exception) {
        String message = exception.getMessage();
        if (StrUtil.isBlank(message)) return "在线目录不可用";
        String normalized = message.trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private long effectiveTtl() {
        return StrUtil.isBlank(lastError) ? REFRESH_TTL_MS : ERROR_TTL_MS;
    }
}
