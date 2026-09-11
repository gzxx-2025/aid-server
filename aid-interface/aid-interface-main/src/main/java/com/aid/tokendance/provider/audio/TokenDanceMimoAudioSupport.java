package com.aid.tokendance.provider.audio;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.spring.SpringUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.model.definition.ModelConfiguredRequestBody;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.provider.ProviderErrorSanitizer;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.util.AudioDurationProber;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDanceHttpResponse;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;
import com.aid.tokendance.provider.common.TokenDanceProtocols;
import com.aid.tokendance.provider.common.TokenDanceResponseMapper;
import com.aid.tokendance.provider.common.TokenDanceTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** TokenDance MiMo 2.5 Chat 音频协议的无状态支持类。 */
@Slf4j
public final class TokenDanceMimoAudioSupport
{
    private static final String MODEL_TTS = "mimo-v2.5-tts";
    private static final String MODEL_DESIGN = "mimo-v2.5-tts-voicedesign";
    private static final String MODEL_CLONE = "mimo-v2.5-tts-voiceclone";
    private static final int MAX_SAMPLE_BYTES = 7_864_320;
    private static final int MAX_RESPONSE_BYTES = 96 * 1024 * 1024;
    private static final int MAX_ENCODED_AUDIO_CHARS = 96 * 1024 * 1024;
    private static final int PCM_SAMPLE_RATE = 24_000;
    private static final int PCM_BYTES_PER_SAMPLE = 2;
    private static final int RAW_AUDIT_BYTES = 100_000;
    private static final Set<String> MODELS = Set.of(MODEL_TTS, MODEL_DESIGN, MODEL_CLONE);
    private static final Set<String> FORMATS = Set.of("wav", "mp3", "pcm", "pcm16");
    private static final Set<String> PRESET_VOICES = Set.of(
            "mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean");
    private static final Set<String> OPTION_KEYS = Set.of(
            "voiceInstruction", "optimizeTextPreview", "referenceSampleUrl", "referenceSampleSha256");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TokenDanceMimoAudioSupport()
    {
    }

    /** 校验 MiMo 模型、文本、音色与非流式输出字段。 */
    public static void validate(AiModelConfigVo config, MediaAudioGenerateRequest request)
    {
        TokenDancePayloadSupport.requireModel(config,
                Set.of(TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS));
        if (request == null)
        {
            fail("missing request", "配音参数不完整");
        }
        if (request.isPreviewMode())
        {
            fail("preview bypasses task", "请创建试听任务");
        }
        String model = model(config, request);
        String format = format(request);
        Map<String, Object> options = options(request);
        TokenDancePayloadSupport.rejectUnknownRequestOptions(config, options, OPTION_KEYS);
        validateSnapshotMetadata(options);
        Boolean optimize = optimize(options);
        String instruction = text(options, "voiceInstruction");
        String targetText = StrUtil.trimToNull(request.getTtsText());
        if (MODEL_DESIGN.equals(model))
        {
            if (StrUtil.isBlank(instruction))
            {
                fail("design instruction missing", "缺少音色描述");
            }
            if (!Boolean.TRUE.equals(optimize) && StrUtil.isBlank(targetText))
            {
                fail("design target missing", "缺少合成文本");
            }
        }
        else if (StrUtil.isBlank(targetText))
        {
            fail("target text missing", "缺少合成文本");
        }
        if (!MODEL_DESIGN.equals(model) && Boolean.TRUE.equals(optimize))
        {
            fail("optimize unsupported", "文本优化不支持");
        }
        if (MODEL_TTS.equals(model))
        {
            String voice = StrUtil.blankToDefault(request.getVoiceCode(), "mimo_default").trim();
            if (!PRESET_VOICES.contains(voice))
            {
                fail("unknown preset voice", "预设音色不支持");
            }
        }
        if (request.getSampleRate() != null && request.getSampleRate() != PCM_SAMPLE_RATE)
        {
            fail("sample rate is not 24k", "采样率仅支持24000");
        }
        if (request.getSpeechRate() != null || request.getLoudnessRate() != null
                || request.getPitch() != null || StrUtil.isNotBlank(request.getEmotion())
                || request.getEmotionScale() != null || Boolean.TRUE.equals(request.getEnableTimestamp()))
        {
            fail("unsupported common controls", "配音参数未适配");
        }
        if (!FORMATS.contains(format))
        {
            fail("unsupported format", "音频格式不支持");
        }
    }

    /** 使用 Spring 中的共享传输层提交 MiMo 非流式音频任务。 */
    public static ProviderSubmitResult submit(AiModelConfigVo config, MediaAudioGenerateRequest request,
            byte[] trustedSample, String mime)
    {
        return submit(config, request, trustedSample, mime,
                SpringUtils.getBean(TokenDanceTransport.class),
                SpringUtils.getBean(TokenDanceAudioRecoveryStore.class));
    }

    /** 使用显式传输层提交，供供应商门面和无网络测试复用。 */
    public static ProviderSubmitResult submit(AiModelConfigVo config, MediaAudioGenerateRequest request,
            byte[] trustedSample, String mime, TokenDanceTransport transport)
    {
        return submit(config, request, trustedSample, mime, transport,
                SpringUtils.getBean(TokenDanceAudioRecoveryStore.class));
    }

    /** 使用显式传输与恢复存储，便于门面保持单一实例。 */
    public static ProviderSubmitResult submit(AiModelConfigVo config, MediaAudioGenerateRequest request,
            byte[] trustedSample, String mime, TokenDanceTransport transport,
            TokenDanceAudioRecoveryStore recoveryStore)
    {
        validate(config, request);
        if (transport == null || recoveryStore == null)
        {
            fail("transport missing", "配音服务未就绪");
        }
        String model = model(config, request);
        validateSample(model, trustedSample, mime);
        return recoveryStore.recoverOrGenerate(config, request, trustedSample,
                () -> generate(config, request, trustedSample, mime, transport, model));
    }

    private static TokenDanceAudioRecoveryStore.GenerationResult generate(
            AiModelConfigVo config, MediaAudioGenerateRequest request,
            byte[] trustedSample, String mime, TokenDanceTransport transport,
            String model)
    {
        byte[] output = null;
        byte[] requestBody = null;
        boolean handedOff = false;
        try
        {
            requestBody = MAPPER.writeValueAsBytes(ModelConfiguredRequestBody.apply(config,
                    body(model, request, trustedSample, mime), request));
            TokenDanceHttpResponse response = transport.exchangeBounded("POST", config,
                    TokenDanceEndpoints.submitPath(config, TokenDanceEndpoints.CHAT_COMPLETIONS), requestBody, Map.of(), MAX_RESPONSE_BYTES);
            if (!response.isSuccessful())
            {
                if (TokenDanceAudioRecoveryStore.isDefinitiveHttpRejection(response))
                {
                    return TokenDanceAudioRecoveryStore.GenerationResult.failure(
                            ProviderSubmitResult.builder().rawResponse(safeAudit(response)).build());
                }
                fail("uncertain http status", "配音结果待确认，已阻止自动重发");
            }
            JsonNode root = MAPPER.readTree(response.openBodyStream());
            JsonNode audio = root.path("choices").path(0).path("message").path("audio");
            String encoded = audio.path("data").asText(null);
            if (StrUtil.isBlank(encoded) || encoded.length() > MAX_ENCODED_AUDIO_CHARS)
            {
                fail("audio base64 missing or large", "音频响应无效");
            }
            output = Base64.getDecoder().decode(encoded);
            if (output.length == 0)
            {
                fail("decoded audio empty", "音频响应无效");
            }
            String format = format(request);
            validateOutput(output, format);
            Long durationMs = durationMs(output, format);
            String preview = StrUtil.trimToNull(
                    root.path("choices").path(0).path("message").path("final_text_preview").asText(null));
            redactAudioData(root);
            addRecovery(root, response.getRecoveryAction());
            handedOff = true;
            return TokenDanceAudioRecoveryStore.GenerationResult.success(
                    new TokenDanceAudioRecoveryStore.GeneratedAudio(output, format, durationMs,
                            TokenDanceResponseMapper.usage(root), MAPPER.writeValueAsString(root), preview));
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (IOException exception)
        {
            fail("transport or upload failed", "配音结果待确认");
            return null;
        }
        catch (Exception exception)
        {
            fail("invalid upstream audio", "音频响应无效");
            return null;
        }
        finally
        {
            if (requestBody != null)
            {
                Arrays.fill(requestBody, (byte) 0);
            }
            if (!handedOff && output != null)
            {
                Arrays.fill(output, (byte) 0);
            }
        }
    }

    private static Map<String, Object> body(String model, MediaAudioGenerateRequest request,
            byte[] trustedSample, String mime)
    {
        Map<String, Object> options = options(request);
        List<Map<String, String>> messages = new ArrayList<>();
        String instruction = StrUtil.blankToDefault(text(options, "voiceInstruction"), "");
        if (MODEL_DESIGN.equals(model) || MODEL_CLONE.equals(model) || StrUtil.isNotBlank(instruction))
        {
            messages.add(Map.of("role", "user", "content", instruction));
        }
        if (StrUtil.isNotBlank(request.getTtsText()))
        {
            messages.add(Map.of("role", "assistant", "content", request.getTtsText().trim()));
        }
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("format", format(request));
        if (MODEL_TTS.equals(model))
        {
            audio.put("voice", StrUtil.blankToDefault(request.getVoiceCode(), "mimo_default").trim());
        }
        else if (MODEL_CLONE.equals(model))
        {
            String canonicalMime = isWavMime(mime) ? "audio/wav" : "audio/mpeg";
            audio.put("voice", "data:" + canonicalMime + ";base64,"
                    + Base64.getEncoder().encodeToString(trustedSample));
        }
        if (MODEL_DESIGN.equals(model) && options.containsKey("optimizeTextPreview"))
        {
            audio.put("optimize_text_preview", optimize(options));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("audio", audio);
        body.put("stream", false);
        return body;
    }

    private static void validateSample(String model, byte[] trustedSample, String mime)
    {
        if (!MODEL_CLONE.equals(model))
        {
            if (trustedSample != null && trustedSample.length > 0)
            {
                fail("sample on non-clone model", "该模型不接收样本");
            }
            return;
        }
        if (trustedSample == null || trustedSample.length == 0
                || trustedSample.length > MAX_SAMPLE_BYTES)
        {
            fail("clone sample size invalid", "克隆样本大小无效");
        }
        if (!isMp3Mime(mime) && !isWavMime(mime))
        {
            fail("clone sample mime invalid", "克隆样本格式无效");
        }
        if ((isWavMime(mime) && !hasAsciiPrefix(trustedSample, 0, "RIFF"))
                || (isWavMime(mime) && !hasAsciiPrefix(trustedSample, 8, "WAVE"))
                || (isMp3Mime(mime) && !looksLikeMp3(trustedSample)))
        {
            fail("clone sample magic mismatch", "克隆样本格式无效");
        }
    }

    private static void validateSnapshotMetadata(Map<String, Object> options)
    {
        Object url = options.get("referenceSampleUrl");
        if (url != null && (!(url instanceof String text) || text.isBlank()
                || text.length() > 4096 || text.regionMatches(true, 0, "data:", 0, 5)))
        {
            fail("sample snapshot url invalid", "克隆样本无效");
        }
        Object sha = options.get("referenceSampleSha256");
        if (sha != null && (!(sha instanceof String text) || !text.matches("(?i)[0-9a-f]{64}")))
        {
            fail("sample snapshot hash invalid", "克隆样本无效");
        }
    }

    private static Boolean optimize(Map<String, Object> options)
    {
        Object value = options.get("optimizeTextPreview");
        if (value == null)
        {
            return Boolean.FALSE;
        }
        if (!(value instanceof Boolean bool))
        {
            fail("optimize flag invalid", "文本优化参数错误");
        }
        return (Boolean) value;
    }

    private static String model(AiModelConfigVo config, MediaAudioGenerateRequest request)
    {
        String model = TokenDancePayloadSupport.upstreamModel(config, request.getModelName())
                .trim().toLowerCase(Locale.ROOT);
        if (!MODELS.contains(model))
        {
            fail("unknown mimo audio model", "配音模型不支持");
        }
        return model;
    }

    private static String format(MediaAudioGenerateRequest request)
    {
        return StrUtil.blankToDefault(request.getAudioFormat(), "wav").trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> options(MediaAudioGenerateRequest request)
    {
        return request.getOptions() == null ? Map.of() : request.getOptions();
    }

    private static String text(Map<String, Object> options, String key)
    {
        Object value = options.get(key);
        if (value == null)
        {
            return null;
        }
        if (!(value instanceof String))
        {
            fail("text option invalid", "配音参数未适配");
        }
        return StrUtil.trimToNull((String) value);
    }

    private static Long durationMs(byte[] bytes, String format)
    {
        if ("pcm".equals(format) || "pcm16".equals(format))
        {
            long samples = bytes.length / PCM_BYTES_PER_SAMPLE;
            return Math.max(1L, (samples * 1000L + PCM_SAMPLE_RATE - 1L) / PCM_SAMPLE_RATE);
        }
        Integer probed = AudioDurationProber.probeDurationMs(bytes);
        return probed == null || probed <= 0 ? null : probed.longValue();
    }

    private static void validateOutput(byte[] bytes, String format)
    {
        boolean valid = switch (format)
        {
            case "wav" -> hasAsciiPrefix(bytes, 0, "RIFF") && hasAsciiPrefix(bytes, 8, "WAVE");
            case "mp3" -> looksLikeMp3(bytes);
            default -> bytes.length % PCM_BYTES_PER_SAMPLE == 0;
        };
        if (!valid)
        {
            fail("output audio magic mismatch", "音频响应无效");
        }
    }

    private static String safeAudit(TokenDanceHttpResponse response)
    {
        if (response == null)
        {
            return null;
        }
        try
        {
            JsonNode root = MAPPER.readTree(response.openBodyStream());
            if (root != null)
            {
                redactAudioData(root);
                addRecovery(root, response.getRecoveryAction());
                return MAPPER.writeValueAsString(root);
            }
        }
        catch (Exception ignored)
        {
        }
        byte[] bytes = response.getBody();
        int length = Math.min(bytes.length, RAW_AUDIT_BYTES);
        return ProviderErrorSanitizer.safeMessage(
                new String(bytes, 0, length, StandardCharsets.UTF_8), "上游请求失败");
    }

    private static void redactAudioData(JsonNode node)
    {
        if (node == null)
        {
            return;
        }
        if (node.isObject())
        {
            ObjectNode object = (ObjectNode) node;
            if (object.has("audio") && object.path("audio").isObject())
            {
                ((ObjectNode) object.path("audio")).put("data", "[omitted]");
            }
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> redactAudioData(object.path(name)));
        }
        else if (node.isArray())
        {
            node.forEach(TokenDanceMimoAudioSupport::redactAudioData);
        }
    }

    private static void addRecovery(JsonNode root, String recoveryAction)
    {
        if (root instanceof ObjectNode object && StrUtil.isNotBlank(recoveryAction))
        {
            object.put("tokendance_recovery_action", recoveryAction);
        }
    }

    private static boolean isWavMime(String mime)
    {
        return "audio/wav".equalsIgnoreCase(StrUtil.blankToDefault(mime, "").trim())
                || "audio/x-wav".equalsIgnoreCase(StrUtil.blankToDefault(mime, "").trim());
    }

    private static boolean isMp3Mime(String mime)
    {
        String normalized = StrUtil.blankToDefault(mime, "").trim();
        return "audio/mpeg".equalsIgnoreCase(normalized) || "audio/mp3".equalsIgnoreCase(normalized);
    }

    private static boolean looksLikeMp3(byte[] bytes)
    {
        return hasAsciiPrefix(bytes, 0, "ID3") || bytes.length >= 2
                && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xe0) == 0xe0;
    }

    private static boolean hasAsciiPrefix(byte[] bytes, int offset, String value)
    {
        if (bytes == null || offset < 0 || bytes.length < offset + value.length())
        {
            return false;
        }
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index < expected.length; index++)
        {
            if (bytes[offset + index] != expected[index])
            {
                return false;
            }
        }
        return true;
    }

    private static void fail(String reason, String message)
    {
        log.info("MiMo 音频请求拒绝: {}", reason);
        throw new ServiceException(message);
    }
}
