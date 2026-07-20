package com.aid.media.provider.impl;

import com.aid.media.provider.ModelIoDump; // 【临时调试】入参/出参落盘工具

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.aid.common.constant.HttpConstants;
import com.aid.common.oss.entity.UploadResult;
import com.aid.common.oss.factory.OssFactory;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.VolcengineTtsConstants;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.provider.AudioProviderClient;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.voice.util.VoiceEmotionCapability;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 豆包语音合成 Provider（火山 openspeech 网关，新版单向流式 HTTP）。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class VolcengineTtsProviderClient implements AudioProviderClient {

    @Override
    public String protocol() {
        return VolcengineTtsConstants.PROTOCOL_TTS;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return false;
        }
        String trimmed = providerCode.trim().toLowerCase();
        // 兼容系统内已有的豆包 TTS provider_code 命名
        return "volcengine_tts".equals(trimmed)
                || "volcengine".equals(trimmed)
                || "doubao".equals(trimmed);
    }

    @Override
    public boolean supportsModel(String modelName) {
        // 精确包含 seed-tts / seed-icl 关键字即视为本 provider 支持。
        if (AudioProviderClient.super.supportsModel(modelName)) {
            return true;
        }
        String normalized = StringUtils.defaultString(modelName).toLowerCase();
        return normalized.contains(VolcengineTtsConstants.MODEL_HINT_SEED_TTS)
                || normalized.contains(VolcengineTtsConstants.MODEL_HINT_SEED_ICL);
    }
    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaAudioGenerateRequest request) {
        validateAuth(modelConfig);   // 校验 X-Api-Key + base_url
        validateRequest(request);    // 校验文本 + 音色

        String requestId = IdUtil.fastSimpleUUID();
        Map<String, Object> body = buildRequestBody(modelConfig, request);
        String json = JSONUtil.toJsonStr(body);
        String url = joinUrl(modelConfig.getBaseUrl(), VolcengineTtsConstants.UNIDIRECTIONAL_PATH);
        String resourceId = resolveResourceId(modelConfig);
        // 音频格式：决定 OSS 落库后缀（默认 mp3）。
        String format = StrUtil.isNotBlank(request.getAudioFormat())
                ? request.getAudioFormat()
                : VolcengineTtsConstants.DEFAULT_AUDIO_FORMAT;

        log.info("VolcengineTts 单向流式合成, model={}, voiceCode={}, textLen={}, resourceId={}, requestId={}",
                modelConfig.getModelCode(),
                request.getVoiceCode(),
                StrUtil.length(request.getTtsText()),
                resourceId,
                requestId);

        String raw;
        try {
            raw = doStream(url, modelConfig, resourceId, json, requestId);
        } catch (Exception ex) {
            log.error("VolcengineTts 合成网络异常, model={}, voiceCode={}",
                    modelConfig.getModelCode(), request.getVoiceCode(), ex);
            // 仅回 rawResponse，主链路据此判失败并退款。
            return ProviderSubmitResult.builder().rawResponse(ex.getMessage()).build();
        }

        StreamParseResult parsed = parseStream(raw);
        if (StrUtil.isNotBlank(parsed.errorLine)) {
            log.error("VolcengineTts 合成上游错误帧, code={}, message={}, model={}",
                    parsed.errorCode, parsed.errorMessage, modelConfig.getModelCode());
            return ProviderSubmitResult.builder().rawResponse(parsed.errorLine).build();
        }
        if (Objects.isNull(parsed.audio) || parsed.audio.length == 0) {
            log.error("VolcengineTts 合成未返回音频分片, model={}, raw={}",
                    modelConfig.getModelCode(), StrUtil.brief(raw, VolcengineTtsConstants.LOG_TEXT_ABBREV_MAX));
            return ProviderSubmitResult.builder().rawResponse(VolcengineTtsConstants.ERR_TTS_NO_AUDIO).build();
        }

        if (request.isPreviewMode()) {
            String audioBase64 = Base64.getEncoder().encodeToString(parsed.audio);
            log.info("VolcengineTts 试听合成成功(base64, 不落库), model={}, audioBytes={}",
                    modelConfig.getModelCode(), parsed.audio.length);
            return ProviderSubmitResult.builder().audioBase64(audioBase64).build();
        }

        String ossUrl = uploadAudioToOss(parsed.audio, format);
        if (StrUtil.isBlank(ossUrl)) {
            log.error("VolcengineTts 音频上传 OSS 失败, model={}, bytes={}",
                    modelConfig.getModelCode(), parsed.audio.length);
            return ProviderSubmitResult.builder().rawResponse(VolcengineTtsConstants.ERR_TTS_STREAM).build();
        }
        log.info("VolcengineTts 合成成功(stream→OSS), model={}, audioBytes={}",
                modelConfig.getModelCode(), parsed.audio.length);
        // ossUrl 非空 → 主链路 handleSubmitResult 直接置 SUCCEEDED + 结算，不进异步轮询。
        return ProviderSubmitResult.builder().ossUrl(ossUrl).build();
    }
    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        // 新版单向流式为同步出音，submit 已落 ossUrl、不回填 providerTaskId，调度层不会轮询本方法。
        // 此处仅作防御：若被异常调用，直接判失败，避免无限轮询。
        log.error("VolcengineTts query 不应被调用（同步流式模型）, providerTaskId={}", providerTaskId);
        return ProviderTaskResult.builder()
                .status(VolcengineTtsConstants.TASK_STATUS_FAILED)
                .errorMessage(VolcengineTtsConstants.ERR_TTS_QUERY)
                .build();
    }
    /**
     * 组装新版单向流式请求体：{@code { "req_params": { ... } }}。
     *
     * @param modelConfig 模型配置
     * @param request     合成请求
     * @return 请求体 Map
     */
    private Map<String, Object> buildRequestBody(AiModelConfigVo modelConfig, MediaAudioGenerateRequest request) {
        Map<String, Object> reqParams = new LinkedHashMap<>();

        // text / ssml（二选一，ssml 优先级高）
        String ssml = MapUtil.getStr(request.getOptions(), VolcengineTtsConstants.OPTIONS_SSML);
        if (StrUtil.isNotBlank(ssml)) {
            reqParams.put(VolcengineTtsConstants.FIELD_SSML, ssml);
        } else {
            reqParams.put(VolcengineTtsConstants.FIELD_TEXT, request.getTtsText());
        }

        // 声音复刻 2.0 子模型（仅 seed-icl-2.0 场景有效）
        String subModel = resolveSubModel(modelConfig, request);
        if (StrUtil.isNotBlank(subModel)) {
            reqParams.put(VolcengineTtsConstants.FIELD_MODEL, subModel);
        }

        // speaker（音色 ID）
        reqParams.put(VolcengineTtsConstants.FIELD_SPEAKER, request.getVoiceCode());

        // audio_params（音频参数）
        reqParams.put(VolcengineTtsConstants.FIELD_AUDIO_PARAMS, buildAudioParams(request));

        // 情感语音指令：2.0 公版音色的情感变化按官方「指令遵循」协议走 context_texts（复刻音色官方暂不支持，不注入）
        applyEmotionInstruction(reqParams, modelConfig, request);

        // 句尾静音时长（新版为 req_params 直属字段）
        Integer silence = MapUtil.getInt(request.getOptions(),
                VolcengineTtsConstants.OPTIONS_SILENCE_DURATION_MS);
        if (Objects.nonNull(silence) && silence > 0) {
            reqParams.put(VolcengineTtsConstants.FIELD_ADDITIONS_SILENCE_DURATION,
                    Math.min(silence, VolcengineTtsConstants.SILENCE_DURATION_MAX));
        }

        // pitch 音调后处理（新版为 req_params.post_process.pitch）
        if (Objects.nonNull(request.getPitch())) {
            int pitch = clamp(request.getPitch(),
                    VolcengineTtsConstants.PITCH_MIN,
                    VolcengineTtsConstants.PITCH_MAX);
            if (pitch != 0) {
                Map<String, Object> postProcess = new LinkedHashMap<>();
                postProcess.put(VolcengineTtsConstants.FIELD_ADDITIONS_POST_PROCESS_PITCH, pitch);
                reqParams.put(VolcengineTtsConstants.FIELD_ADDITIONS_POST_PROCESS, postProcess);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(VolcengineTtsConstants.FIELD_REQ_PARAMS, reqParams);
        return body;
    }

    /**
     * 组装 audio_params（按新版文档可用字段：format / sample_rate / speech_rate / loudness_rate / enable_subtitle）。
     *
     * @param request 合成请求
     * @return audio_params Map
     */
    private Map<String, Object> buildAudioParams(MediaAudioGenerateRequest request) {
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put(VolcengineTtsConstants.FIELD_FORMAT,
                StrUtil.isNotBlank(request.getAudioFormat())
                        ? request.getAudioFormat()
                        : VolcengineTtsConstants.DEFAULT_AUDIO_FORMAT);
        audio.put(VolcengineTtsConstants.FIELD_SAMPLE_RATE,
                Objects.nonNull(request.getSampleRate())
                        ? request.getSampleRate()
                        : VolcengineTtsConstants.DEFAULT_SAMPLE_RATE);

        if (Objects.nonNull(request.getSpeechRate())) {
            audio.put(VolcengineTtsConstants.FIELD_SPEECH_RATE,
                    clamp(request.getSpeechRate(),
                            VolcengineTtsConstants.SPEECH_RATE_MIN,
                            VolcengineTtsConstants.SPEECH_RATE_MAX));
        }
        if (Objects.nonNull(request.getLoudnessRate())) {
            audio.put(VolcengineTtsConstants.FIELD_LOUDNESS_RATE,
                    clamp(request.getLoudnessRate(),
                            VolcengineTtsConstants.LOUDNESS_RATE_MIN,
                            VolcengineTtsConstants.LOUDNESS_RATE_MAX));
        }
        // 字级时间戳（新版字段名 enable_subtitle）
        if (Boolean.TRUE.equals(request.getEnableTimestamp())) {
            audio.put(VolcengineTtsConstants.FIELD_ENABLE_SUBTITLE, Boolean.TRUE);
        }
        // 情感 + 情绪值：多情感音色官方字段（emotion 编码来自模型能力声明，不支持的音色上游忽略）；
        // emotion_scale 官方约定仅在设置了 emotion 后生效，范围 1~5、缺省 4
        if (StrUtil.isNotBlank(request.getEmotion())) {
            audio.put(VolcengineTtsConstants.FIELD_EMOTION, request.getEmotion().trim());
            int emotionScale = Objects.nonNull(request.getEmotionScale())
                    ? clamp(request.getEmotionScale(),
                            VolcengineTtsConstants.EMOTION_SCALE_MIN,
                            VolcengineTtsConstants.EMOTION_SCALE_MAX)
                    : VolcengineTtsConstants.DEFAULT_EMOTION_SCALE;
            audio.put(VolcengineTtsConstants.FIELD_EMOTION_SCALE, emotionScale);
        }
        return audio;
    }

    /**
     * 注入情感语音指令（req_params.context_texts）。
     * 仅 2.0 公版音色资源（seed-tts-2.0）注入：官方声明其情感变化通过「语音指令」实现，
     * 且指令文本不参与计费；复刻音色（seed-icl-*）官方暂不支持语音指令，不注入。
     * 指令文案由供应商情感编码翻译为中文标签生成，未收录的编码原样嵌入，不阻断。
     *
     * @param reqParams   请求参数容器
     * @param modelConfig 模型配置
     * @param request     合成请求
     */
    private void applyEmotionInstruction(Map<String, Object> reqParams, AiModelConfigVo modelConfig,
                                         MediaAudioGenerateRequest request) {
        if (StrUtil.isBlank(request.getEmotion())) {
            return;
        }
        String resourceId = resolveResourceId(modelConfig);
        if (!VolcengineTtsConstants.RESOURCE_ID_SEED_TTS_2_0.equalsIgnoreCase(resourceId)) {
            return;
        }
        String label = VoiceEmotionCapability.labelOf(request.getEmotion());
        String instruction = String.format(VolcengineTtsConstants.EMOTION_INSTRUCTION_TEMPLATE, label);
        reqParams.put(VolcengineTtsConstants.FIELD_CONTEXT_TEXTS, List.of(instruction));
    }
    /**
     * 解析单向流式响应（按行分帧）：。
     *
     * @param body 完整响应体
     * @return 解析结果（音频字节 / 错误信息）
     */
    private StreamParseResult parseStream(String body) {
        StreamParseResult result = new StreamParseResult();
        if (StrUtil.isBlank(body)) {
            return result;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        // 每帧是一行完整 JSON，按行切分逐帧解析。
        for (String rawLine : body.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            JsonNode node;
            try {
                node = ProviderResponseHelper.readTree(line);
            } catch (Exception ignored) {
                // 非完整 JSON 行跳过（防御）
                continue;
            }
            if (Objects.isNull(node)) {
                continue;
            }
            Integer code = ProviderResponseHelper.readInt(node, VolcengineTtsConstants.RESP_CODE);
            if (Objects.isNull(code)) {
                continue;
            }
            if (code == VolcengineTtsConstants.STREAM_CODE_CHUNK) {
                // 数据分片：解码 base64 累加
                String data = ProviderResponseHelper.readText(node, VolcengineTtsConstants.RESP_DATA);
                if (StrUtil.isNotBlank(data)) {
                    try {
                        buffer.write(Base64.getDecoder().decode(data));
                    } catch (Exception e) {
                        log.warn("VolcengineTts base64 分片解码失败, len={}", StrUtil.length(data));
                    }
                }
            } else if (code == VolcengineTtsConstants.STREAM_CODE_END) {
                // 流结束（成功）
                break;
            } else {
                // 其它 code 视为错误帧
                result.errorCode = code;
                result.errorMessage = ProviderResponseHelper.readText(node, VolcengineTtsConstants.RESP_MESSAGE);
                result.errorLine = line;
                break;
            }
        }
        result.audio = buffer.toByteArray();
        return result;
    }

    /** 流式解析结果载体。 */
    private static final class StreamParseResult {
        /** 拼接后的完整音频字节 */
        private byte[] audio;
        /** 错误码（错误帧时非空） */
        private Integer errorCode;
        /** 错误信息（错误帧时非空） */
        private String errorMessage;
        /** 错误帧原文（错误帧时非空，供主链路解析展示） */
        private String errorLine;
    }
    /**
     * 将完整音频字节上传 OSS，返回系统可访问 URL。
     *
     * @param audioBytes 音频字节
     * @param format     音频格式（mp3 / wav / ogg_opus / pcm）
     * @return OSS URL；失败返回 null
     */
    private String uploadAudioToOss(byte[] audioBytes, String format) {
        try {
            String fmt = StrUtil.isNotBlank(format)
                    ? format.trim().toLowerCase()
                    : VolcengineTtsConstants.DEFAULT_AUDIO_FORMAT;
            String suffix;
            String contentType;
            switch (fmt) {
                case "wav":
                    suffix = VolcengineTtsConstants.AUDIO_SUFFIX_WAV;
                    contentType = VolcengineTtsConstants.AUDIO_CONTENT_TYPE_WAV;
                    break;
                case "ogg":
                case "ogg_opus":
                    suffix = VolcengineTtsConstants.AUDIO_SUFFIX_OGG;
                    contentType = VolcengineTtsConstants.AUDIO_CONTENT_TYPE_OGG;
                    break;
                case "pcm":
                    suffix = VolcengineTtsConstants.AUDIO_SUFFIX_PCM;
                    contentType = VolcengineTtsConstants.AUDIO_CONTENT_TYPE_PCM;
                    break;
                default:
                    suffix = VolcengineTtsConstants.AUDIO_SUFFIX_MP3;
                    contentType = VolcengineTtsConstants.AUDIO_CONTENT_TYPE_MP3;
            }
            UploadResult uploadResult = OssFactory.instance().uploadSuffix(audioBytes, suffix, contentType);
            return uploadResult.getUrl();
        } catch (Exception e) {
            log.error("VolcengineTts 音频 OSS 上传异常, error={}", e.getMessage(), e);
            return null;
        }
    }
    /**
     * 解析 X-Api-Resource-Id。
     * 优先取 {@code aid_ai_model.capability_json.resourceId}；缺省时按 model_code 兜底：
     * 以 {@code seed-icl-} / {@code seed-tts-} 前缀直接当作 resource_id；其它兜底 seed-tts-2.0。
     */
    private String resolveResourceId(AiModelConfigVo modelConfig) {
        if (Objects.nonNull(modelConfig) && StrUtil.isNotBlank(modelConfig.getCapabilityJson())) {
            try {
                JsonNode node = ProviderResponseHelper.readTree(modelConfig.getCapabilityJson());
                String fromCapability = ProviderResponseHelper.readText(node,
                        VolcengineTtsConstants.CAPABILITY_KEY_RESOURCE_ID);
                if (StrUtil.isNotBlank(fromCapability)) {
                    return fromCapability;
                }
            } catch (Exception ignored) {
                // 解析失败走兜底
            }
        }
        String code = Objects.nonNull(modelConfig)
                ? StringUtils.defaultString(com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(modelConfig, null))
                : "";
        String lower = code.toLowerCase();
        if (lower.startsWith(VolcengineTtsConstants.RESOURCE_ID_SEED_ICL_2_0)) {
            return VolcengineTtsConstants.RESOURCE_ID_SEED_ICL_2_0;
        }
        if (lower.startsWith(VolcengineTtsConstants.RESOURCE_ID_SEED_ICL_1_0)) {
            return VolcengineTtsConstants.RESOURCE_ID_SEED_ICL_1_0;
        }
        if (lower.startsWith(VolcengineTtsConstants.RESOURCE_ID_SEED_TTS_1_0)) {
            return VolcengineTtsConstants.RESOURCE_ID_SEED_TTS_1_0;
        }
        if (lower.startsWith(VolcengineTtsConstants.RESOURCE_ID_SEED_TTS_2_0)) {
            return VolcengineTtsConstants.RESOURCE_ID_SEED_TTS_2_0;
        }
        return VolcengineTtsConstants.RESOURCE_ID_SEED_TTS_2_0;
    }

    /**
     * 解析声音复刻 2.0 的子模型（req_params.model），仅 seed-icl-2.0 需要。
     * 优先取请求 options.subModel，其次 capability_json.defaultSubModel。
     */
    private String resolveSubModel(AiModelConfigVo modelConfig, MediaAudioGenerateRequest request) {
        String resourceId = resolveResourceId(modelConfig);
        if (!VolcengineTtsConstants.RESOURCE_ID_SEED_ICL_2_0.equalsIgnoreCase(resourceId)) {
            return null;
        }
        String fromOptions = MapUtil.getStr(request.getOptions(), VolcengineTtsConstants.OPTIONS_SUB_MODEL);
        if (StrUtil.isNotBlank(fromOptions)) {
            return fromOptions;
        }
        if (Objects.nonNull(modelConfig) && StrUtil.isNotBlank(modelConfig.getCapabilityJson())) {
            try {
                JsonNode node = ProviderResponseHelper.readTree(modelConfig.getCapabilityJson());
                return ProviderResponseHelper.readText(node,
                        VolcengineTtsConstants.CAPABILITY_KEY_DEFAULT_SUB_MODEL);
            } catch (Exception ignored) {
                // 解析失败返回 null 让上游使用默认子模型
            }
        }
        return null;
    }
    /**
     * 发起单向流式合成请求，返回完整响应体（按行分帧的 JSON 文本）。
     * 鉴权：仅 {@code X-Api-Key}（api_key）+ {@code X-Api-Resource-Id} + {@code X-Api-Request-Id}。
     */
    private String doStream(String url, AiModelConfigVo modelConfig, String resourceId, String json, String requestId) {
        ModelIoDump.req(url, json); // 【临时调试】记录下发上游入参
        try (HttpResponse response = HttpRequest.post(url)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .header(VolcengineTtsConstants.HEADER_API_KEY, modelConfig.getApiKey())
                .header(VolcengineTtsConstants.HEADER_RESOURCE_ID, resourceId)
                .header(VolcengineTtsConstants.HEADER_REQUEST_ID, requestId)
                .body(json)
                .timeout(VolcengineTtsConstants.STREAM_HTTP_TIMEOUT_MS)
                .execute()) {
            String body = response.body();
            // 响应含大量 base64，落盘只记截断片段，避免日志爆量。
            ModelIoDump.resp(url, StrUtil.brief(body, VolcengineTtsConstants.LOG_TEXT_ABBREV_MAX));
            return body;
        }
    }

    /**
     * 鉴权校验：新版仅需 X-Api-Key（api_key）+ base_url。
     */
    private void validateAuth(AiModelConfigVo modelConfig) {
        if (Objects.isNull(modelConfig)
                || StrUtil.isBlank(modelConfig.getApiKey())) {
            throw new IllegalStateException(VolcengineTtsConstants.ERR_APP_ID_EMPTY);
        }
        if (StrUtil.isBlank(modelConfig.getBaseUrl())) {
            throw new IllegalStateException(VolcengineTtsConstants.ERR_APP_ID_EMPTY);
        }
    }

    private void validateRequest(MediaAudioGenerateRequest request) {
        if (Objects.isNull(request) || StrUtil.isBlank(request.getTtsText())) {
            throw new IllegalArgumentException(VolcengineTtsConstants.ERR_TEXT_EMPTY);
        }
        if (StrUtil.isBlank(request.getVoiceCode())) {
            throw new IllegalArgumentException(VolcengineTtsConstants.ERR_VOICE_EMPTY);
        }
    }

    private String joinUrl(String base, String path) {
        if (StrUtil.isBlank(base)) {
            throw new IllegalStateException(VolcengineTtsConstants.ERR_APP_ID_EMPTY);
        }
        String trimmed = base.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + path;
    }

    private int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}
