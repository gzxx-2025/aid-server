package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 将普通 HTTP(S) 多模态 URL 转换为 Gemini Files URI，并负责临时资源回收。 */
@Slf4j
public final class GeminiFileUploadSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long DEFAULT_DOWNLOAD_LIMIT_BYTES = 64L * 1024L * 1024L;
    private static final long ACTIVE_WAIT_MILLIS = 120_000L;

    private GeminiFileUploadSupport() {
    }

    public static Session prepare(AiModelConfigVo model, MediaTextGenerateRequest request) {
        Session session = new Session(model, request);
        try {
            session.prepare();
            return session;
        } catch (RuntimeException error) {
            session.close();
            throw error;
        }
    }

    public static final class Session implements AutoCloseable {
        private final AiModelConfigVo model;
        private final MediaTextGenerateRequest request;
        private final List<UploadedFile> uploadedFiles = new ArrayList<>();
        private final List<OriginalUrl> originalUrls = new ArrayList<>();

        private Session(AiModelConfigVo model, MediaTextGenerateRequest request) {
            this.model = model;
            this.request = request;
        }

        private void prepare() {
            if (request == null || request.getMessages() == null) {
                return;
            }
            for (MediaTextGenerateRequest.TextMessageItem message : request.getMessages()) {
                if (message == null || message.getParts() == null) {
                    continue;
                }
                for (MediaTextGenerateRequest.TextContentPart part : message.getParts()) {
                    if (part == null || "text".equalsIgnoreCase(part.getType())
                            || StrUtil.isBlank(part.getUrl())) {
                        continue;
                    }
                    URI uri = URI.create(part.getUrl().trim());
                    if (!"http".equalsIgnoreCase(uri.getScheme())
                            && !"https".equalsIgnoreCase(uri.getScheme())) {
                        continue;
                    }
                    String original = part.getUrl();
                    UploadedFile uploaded = uploadRemotePart(part);
                    uploadedFiles.add(uploaded);
                    originalUrls.add(new OriginalUrl(part, original));
                    part.setUrl(uploaded.uri());
                }
            }
        }

        private UploadedFile uploadRemotePart(MediaTextGenerateRequest.TextContentPart part) {
            requireConfig();
            Path temp = null;
            String uploadedName = null;
            try {
                temp = download(part);
                JsonNode file = upload(temp, part);
                String name = file.path("name").asText(null);
                String uri = file.path("uri").asText(null);
                if (StrUtil.isBlank(name) || StrUtil.isBlank(uri)) {
                    throw new ServiceException("媒体上传失败");
                }
                uploadedName = name;
                file = awaitActive(name, file);
                return new UploadedFile(name, file.path("uri").asText(uri), temp);
            } catch (ServiceException error) {
                deleteRemote(uploadedName);
                deleteTemp(temp);
                throw error;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                deleteRemote(uploadedName);
                deleteTemp(temp);
                throw new ServiceException("媒体上传失败");
            } catch (Exception error) {
                log.error("Gemini 输入文件转换失败: modelCode={}, type={}, errorType={}",
                        model == null ? null : model.getModelCode(), part.getType(),
                        error.getClass().getSimpleName());
                deleteRemote(uploadedName);
                deleteTemp(temp);
                throw new ServiceException("媒体上传失败");
            }
        }

        private Path download(MediaTextGenerateRequest.TextContentPart part) throws Exception {
            long declaredLimit = capabilityFileLimitBytes(part.getType());
            long maxBytes = declaredLimit > 0L ? declaredLimit : DEFAULT_DOWNLOAD_LIMIT_BYTES;
            if (part.getSizeBytes() != null && part.getSizeBytes() > maxBytes) {
                throw new ServiceException("媒体文件过大");
            }
            HttpRequest download = HttpRequest.newBuilder(URI.create(part.getUrl().trim()))
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "AID-GeminiMedia/1.0")
                    .GET().build();
            HttpResponse<InputStream> response = HTTP.send(download, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                throw new ServiceException("媒体读取失败");
            }
            String responseMime = response.headers().firstValue("Content-Type")
                    .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT)).orElse(null);
            if (StrUtil.isNotBlank(responseMime) && !mimeMatchesType(responseMime, part.getType())) {
                closeQuietly(response.body());
                throw new ServiceException("媒体格式不支持");
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > maxBytes) {
                closeQuietly(response.body());
                throw new ServiceException("媒体文件过大");
            }
            Path temp = Files.createTempFile("aid-gemini-input-", safeSuffix(part.getUrl()));
            long total = 0L;
            try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    total += read;
                    if (total > maxBytes) {
                        throw new ServiceException("媒体文件过大");
                    }
                    output.write(buffer, 0, read);
                }
            } catch (Exception error) {
                deleteTemp(temp);
                throw error;
            }
            return temp;
        }

        private JsonNode upload(Path temp, MediaTextGenerateRequest.TextContentPart part) throws Exception {
            long size = Files.size(temp);
            String mime = StrUtil.blankToDefault(part.getMimeType(), "application/octet-stream");
            String metadata = MAPPER.writeValueAsString(java.util.Map.of("file",
                    java.util.Map.of("display_name", temp.getFileName().toString())));
            HttpRequest start = HttpRequest.newBuilder(URI.create(apiOrigin() + "/upload/v1beta/files"))
                    .timeout(Duration.ofSeconds(30))
                    .header("x-goog-api-key", model.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("X-Goog-Upload-Protocol", "resumable")
                    .header("X-Goog-Upload-Command", "start")
                    .header("X-Goog-Upload-Header-Content-Length", String.valueOf(size))
                    .header("X-Goog-Upload-Header-Content-Type", mime)
                    .POST(HttpRequest.BodyPublishers.ofString(metadata, StandardCharsets.UTF_8)).build();
            HttpResponse<String> startResponse = HTTP.send(start,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (startResponse.statusCode() < 200 || startResponse.statusCode() >= 300) {
                throw new ServiceException("媒体上传失败");
            }
            String uploadUrl = startResponse.headers().firstValue("X-Goog-Upload-URL").orElse(null);
            if (StrUtil.isBlank(uploadUrl)) {
                throw new ServiceException("媒体上传失败");
            }
            HttpRequest finish = HttpRequest.newBuilder(URI.create(uploadUrl))
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Length", String.valueOf(size))
                    .header("X-Goog-Upload-Offset", "0")
                    .header("X-Goog-Upload-Command", "upload, finalize")
                    .POST(HttpRequest.BodyPublishers.ofFile(temp)).build();
            HttpResponse<String> finishResponse = HTTP.send(finish,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (finishResponse.statusCode() < 200 || finishResponse.statusCode() >= 300) {
                throw new ServiceException("媒体上传失败");
            }
            JsonNode root = MAPPER.readTree(finishResponse.body());
            return root.path("file").isObject() ? root.path("file") : root;
        }

        private JsonNode awaitActive(String name, JsonNode initial) throws Exception {
            JsonNode file = initial;
            long deadline = System.currentTimeMillis() + ACTIVE_WAIT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                String state = file.path("state").asText("ACTIVE").toUpperCase(Locale.ROOT);
                if ("ACTIVE".equals(state) || StrUtil.isBlank(state)) {
                    return file;
                }
                if ("FAILED".equals(state)) {
                    throw new ServiceException("媒体处理失败");
                }
                Thread.sleep(2000L);
                HttpRequest poll = HttpRequest.newBuilder(URI.create(apiOrigin() + "/v1beta/" + name))
                        .timeout(Duration.ofSeconds(30)).header("x-goog-api-key", model.getApiKey()).GET().build();
                HttpResponse<String> response = HTTP.send(poll,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new ServiceException("媒体处理失败");
                }
                JsonNode root = MAPPER.readTree(response.body());
                file = root.path("file").isObject() ? root.path("file") : root;
            }
            throw new ServiceException("媒体处理超时");
        }

        private long capabilityFileLimitBytes(String type) {
            if (model == null || StrUtil.isBlank(model.getCapabilityJson()) || StrUtil.isBlank(type)) {
                return 0L;
            }
            try {
                String name = "maxInput" + Character.toUpperCase(type.charAt(0))
                        + type.substring(1).toLowerCase(Locale.ROOT) + "FileSizeMb";
                long mb = MAPPER.readTree(model.getCapabilityJson()).path(name).asLong(0L);
                return mb <= 0L || mb > Long.MAX_VALUE / 1024L / 1024L
                        ? 0L : mb * 1024L * 1024L;
            } catch (Exception ignored) {
                return 0L;
            }
        }

        private void requireConfig() {
            if (model == null || StrUtil.isBlank(model.getBaseUrl()) || StrUtil.isBlank(model.getApiKey())) {
                throw new ServiceException("模型配置缺失");
            }
        }

        private String apiOrigin() {
            URI base = URI.create(model.getBaseUrl().trim());
            if (StrUtil.isBlank(base.getScheme()) || StrUtil.isBlank(base.getAuthority())) {
                throw new ServiceException("模型配置缺失");
            }
            return base.getScheme() + "://" + base.getAuthority();
        }

        @Override
        public void close() {
            for (int i = originalUrls.size() - 1; i >= 0; i--) {
                OriginalUrl original = originalUrls.get(i);
                original.part().setUrl(original.url());
            }
            for (int i = uploadedFiles.size() - 1; i >= 0; i--) {
                UploadedFile file = uploadedFiles.get(i);
                deleteRemote(file.name());
                deleteTemp(file.temp());
            }
            originalUrls.clear();
            uploadedFiles.clear();
        }

        private void deleteRemote(String name) {
            if (StrUtil.isBlank(name)) {
                return;
            }
            try {
                HttpRequest delete = HttpRequest.newBuilder(URI.create(apiOrigin() + "/v1beta/" + name))
                        .timeout(Duration.ofSeconds(30)).header("x-goog-api-key", model.getApiKey()).DELETE().build();
                HttpResponse<Void> response = HTTP.send(delete, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("Gemini 临时文件删除失败: modelCode={}, status={}",
                            model.getModelCode(), response.statusCode());
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                log.warn("Gemini 临时文件删除异常: modelCode={}, errorType={}",
                        model == null ? null : model.getModelCode(), error.getClass().getSimpleName());
            }
        }
    }

    private static String safeSuffix(String value) {
        try {
            String path = URI.create(value).getPath();
            int dot = path == null ? -1 : path.lastIndexOf('.');
            String suffix = dot >= 0 && path.length() - dot <= 12 ? path.substring(dot) : ".bin";
            return suffix.matches("\\.[A-Za-z0-9]{1,10}") ? suffix : ".bin";
        } catch (Exception ignored) {
            return ".bin";
        }
    }

    private static boolean mimeMatchesType(String mime, String type) {
        String normalizedType = StrUtil.blankToDefault(type, "").trim().toLowerCase(Locale.ROOT);
        return switch (normalizedType) {
            case "image", "video", "audio" -> mime.startsWith(normalizedType + "/");
            case "document" -> mime.startsWith("text/") || mime.equals("application/pdf")
                    || mime.equals("application/json") || mime.equals("application/xml")
                    || mime.equals("application/octet-stream");
            default -> false;
        };
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (Exception ignored) {
        }
    }

    private static void deleteTemp(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (Exception error) {
            log.warn("Gemini 本地临时文件删除失败: file={}, errorType={}",
                    temp.getFileName(), error.getClass().getSimpleName());
        }
    }

    private record UploadedFile(String name, String uri, Path temp) {
    }

    private record OriginalUrl(MediaTextGenerateRequest.TextContentPart part, String url) {
    }
}
