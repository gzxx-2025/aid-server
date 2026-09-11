package com.aid.tokendance.provider.image;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.common.oss.factory.OssFactory;
import com.aid.tokendance.provider.common.TokenDanceResponseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** TokenDance Ark 图片 JSON/SSE 响应解码，Base64 只在内存解码并立即转存。 */
final class TokenDanceImageResponseSupport
{
    private static final int MAX_STREAM_BYTES = 96 * 1024 * 1024;
    private static final int MAX_EVENT_BYTES = 80 * 1024 * 1024;
    private static final int MAX_SINGLE_IMAGE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_TOTAL_IMAGE_BYTES = 96 * 1024 * 1024;
    private static final int MAX_EVENTS = 10_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TokenDanceImageResponseSupport()
    {
    }

    static DecodedImages decodeJson(JsonNode root, String requestedOutputFormat)
    {
        if (root == null || !root.isObject())
        {
            throw new ServiceException("图片响应无效");
        }
        Collector collector = new Collector(requestedOutputFormat);
        collector.captureImages(root);
        return collector.finish(root, 0);
    }

    static DecodedImages decodeSse(InputStream input, String requestedOutputFormat)
            throws IOException
    {
        Collector collector = new Collector(requestedOutputFormat);
        SseState state = new SseState(collector);
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        long total = 0;
        int value;
        while ((value = input.read()) >= 0)
        {
            if (++total > MAX_STREAM_BYTES)
            {
                throw new ServiceException("图片响应过大");
            }
            if (value == '\n')
            {
                state.line(line.toByteArray());
                line.reset();
                continue;
            }
            if (line.size() >= MAX_EVENT_BYTES)
            {
                throw new ServiceException("图片事件过大");
            }
            line.write(value);
        }
        if (line.size() > 0)
        {
            state.line(line.toByteArray());
        }
        state.completePending();
        if (!state.completed || !state.done)
        {
            throw new ServiceException("图片响应未完成");
        }
        return collector.finish(state.terminal, state.partialFailures);
    }

    private static final class SseState
    {
        private final Collector collector;
        private final StringBuilder data = new StringBuilder();
        private int events;
        private int partialFailures;
        private boolean completed;
        private boolean done;
        private JsonNode terminal;

        private SseState(Collector collector)
        {
            this.collector = collector;
        }

        private void line(byte[] bytes)
        {
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\r')
            {
                length--;
            }
            String value = new String(bytes, 0, length, StandardCharsets.UTF_8);
            if (value.isEmpty())
            {
                dispatch();
            }
            else if (value.startsWith("data:"))
            {
                if (!data.isEmpty())
                {
                    data.append('\n');
                }
                data.append(value.substring(5).stripLeading());
            }
        }

        private void completePending()
        {
            if (!data.isEmpty())
            {
                dispatch();
            }
        }

        private void dispatch()
        {
            if (data.isEmpty())
            {
                return;
            }
            if (++events > MAX_EVENTS)
            {
                throw new ServiceException("图片事件过多");
            }
            String event = data.toString();
            data.setLength(0);
            if ("[DONE]".equals(event))
            {
                done = true;
                return;
            }
            JsonNode root;
            try
            {
                root = MAPPER.readTree(event);
            }
            catch (Exception exception)
            {
                throw new ServiceException("图片事件无效");
            }
            if (root == null || !root.isObject())
            {
                throw new ServiceException("图片事件无效");
            }
            String type = StrUtil.blankToDefault(
                    root.path("type").asText(null), root.path("event").asText(null));
            if ("image_generation.partial_failed".equals(type))
            {
                partialFailures++;
            }
            else if ("image_generation.partial_succeeded".equals(type))
            {
                collector.captureImages(root);
            }
            else if ("image_generation.completed".equals(type))
            {
                collector.captureImages(root);
                completed = true;
                terminal = root;
            }
        }
    }

    private static final class Collector
    {
        private final String requestedOutputFormat;
        private final Set<String> urls = new LinkedHashSet<>();
        private final Set<String> imageHashes = new LinkedHashSet<>();
        private long totalDecodedBytes;

        private Collector(String requestedOutputFormat)
        {
            this.requestedOutputFormat = StrUtil.trimToNull(requestedOutputFormat);
        }

        private void captureImages(JsonNode root)
        {
            JsonNode data = root.path("data");
            if (data.isArray())
            {
                data.forEach(this::captureItem);
            }
            else if (data.isObject())
            {
                captureItem(data);
            }
            captureItem(root);
        }

        private void captureItem(JsonNode item)
        {
            if (item == null || !item.isObject())
            {
                return;
            }
            String url = StrUtil.trimToNull(item.path("url").asText(null));
            if (url != null)
            {
                requirePublicResultUrl(url);
                urls.add(url);
            }
            String encoded = StrUtil.trimToNull(item.path("b64_json").asText(null));
            if (encoded != null)
            {
                captureBase64(encoded);
                if (item instanceof ObjectNode object)
                {
                    object.put("b64_json", "[omitted]");
                }
            }
        }

        private void captureBase64(String encoded)
        {
            String pure = encoded;
            int comma = encoded.indexOf(',');
            if (encoded.regionMatches(true, 0, "data:image/", 0, 11))
            {
                if (comma < 0 || !encoded.substring(0, comma).toLowerCase(Locale.ROOT)
                        .endsWith(";base64"))
                {
                    throw new ServiceException("图片响应无效");
                }
                pure = encoded.substring(comma + 1);
            }
            if (pure.length() > ((long) MAX_SINGLE_IMAGE_BYTES + 2L) / 3L * 4L + 4L)
            {
                throw new ServiceException("图片响应过大");
            }
            byte[] bytes;
            try
            {
                bytes = Base64.getDecoder().decode(pure);
            }
            catch (IllegalArgumentException exception)
            {
                throw new ServiceException("图片响应无效");
            }
            try
            {
                if (bytes.length == 0 || bytes.length > MAX_SINGLE_IMAGE_BYTES
                        || totalDecodedBytes + bytes.length > MAX_TOTAL_IMAGE_BYTES)
                {
                    throw new ServiceException("图片响应过大");
                }
                ImageType type = ImageType.detect(bytes);
                if (requestedOutputFormat != null
                        && !type.matches(requestedOutputFormat))
                {
                    throw new ServiceException("图片格式不一致");
                }
                String hash = java.util.HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes));
                if (imageHashes.add(hash))
                {
                    totalDecodedBytes += bytes.length;
                    urls.add(OssFactory.instance().uploadSuffix(
                            bytes, type.suffix, type.contentType).getUrl());
                }
            }
            catch (ServiceException exception)
            {
                throw exception;
            }
            catch (Exception exception)
            {
                throw new ServiceException("图片转存失败");
            }
            finally
            {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        }

        private DecodedImages finish(JsonNode terminal, int partialFailures)
        {
            JsonNode audit = terminal == null ? MAPPER.createObjectNode() : terminal.deepCopy();
            redactBase64(audit);
            Map<String, Object> usage = usage(terminal);
            return new DecodedImages(List.copyOf(urls), audit, usage, partialFailures);
        }
    }

    private enum ImageType
    {
        PNG(".png", "image/png"),
        JPEG(".jpg", "image/jpeg");

        private final String suffix;
        private final String contentType;

        ImageType(String suffix, String contentType)
        {
            this.suffix = suffix;
            this.contentType = contentType;
        }

        private boolean matches(String configured)
        {
            String normalized = configured.trim().toLowerCase(Locale.ROOT);
            return this == PNG ? "png".equals(normalized)
                    : "jpeg".equals(normalized) || "jpg".equals(normalized);
        }

        private static ImageType detect(byte[] bytes)
        {
            if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89
                    && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G')
            {
                return PNG;
            }
            if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff)
            {
                return JPEG;
            }
            throw new ServiceException("图片响应无效");
        }
    }

    static String audit(DecodedImages decoded, String recoveryAction)
    {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("response", decoded.audit());
        if (decoded.partialFailures() > 0)
        {
            root.put("partial_failed", decoded.partialFailures());
        }
        String recovery = StrUtil.trimToNull(recoveryAction);
        if (recovery != null && recovery.length() <= 64
                && recovery.indexOf('\r') < 0 && recovery.indexOf('\n') < 0)
        {
            root.put("tokendance_recovery_action", recovery);
        }
        try
        {
            return MAPPER.writeValueAsString(root);
        }
        catch (Exception exception)
        {
            throw new ServiceException("图片响应无效");
        }
    }

    private static Map<String, Object> usage(JsonNode root)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> tokens = TokenDanceResponseMapper.usage(root);
        if (tokens != null)
        {
            result.putAll(tokens);
        }
        JsonNode usage = root == null ? null : root.path("usage");
        if (usage != null && usage.isObject())
        {
            copyNonNegative(usage, result, "generated_images");
            JsonNode search = usage.path("tool_usage").path("web_search");
            if (search.isIntegralNumber() && search.longValue() >= 0)
            {
                result.put("web_search_calls", search.longValue());
                result.put("tool_usage", Map.of("web_search", search.longValue()));
            }
        }
        return result.isEmpty() ? null : Map.copyOf(result);
    }

    private static void copyNonNegative(JsonNode source, Map<String, Object> target,
            String key)
    {
        JsonNode value = source.path(key);
        if (value.isIntegralNumber() && value.longValue() >= 0)
        {
            target.put(key, value.longValue());
        }
    }

    private static void requirePublicResultUrl(String value)
    {
        try
        {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(
                    StrUtil.blankToDefault(uri.getScheme(), "").toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getUserInfo() != null)
            {
                throw new IllegalArgumentException("invalid result URL");
            }
        }
        catch (IllegalArgumentException exception)
        {
            throw new ServiceException("图片地址无效");
        }
    }

    private static void redactBase64(JsonNode node)
    {
        if (node == null)
        {
            return;
        }
        if (node instanceof ObjectNode object)
        {
            if (object.has("b64_json"))
            {
                object.put("b64_json", "[omitted]");
            }
            List<String> fields = new ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            fields.forEach(name -> redactBase64(object.path(name)));
        }
        else if (node.isArray())
        {
            node.forEach(TokenDanceImageResponseSupport::redactBase64);
        }
    }

    record DecodedImages(List<String> urls, JsonNode audit,
                         Map<String, Object> usage, int partialFailures)
    {
    }
}
