package com.aid.media.provider.impl;

import com.aid.media.provider.ModelIoDump; // 【临时调试】入参/出参落盘工具

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.aid.common.constant.HttpConstants;
import com.aid.common.oss.entity.UploadResult;
import com.aid.common.oss.factory.OssFactory;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MinimaxTtsConstants;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.provider.AudioProviderClient;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * MiniMax 语音合成 Provider（非流式同步 T2A V2）。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class MinimaxTtsProviderClient implements AudioProviderClient
{
    @Override
    public String protocol()
    {
        return MinimaxTtsConstants.PROTOCOL_TTS;
    }

    @Override
    public boolean supportsProviderCode(String providerCode)
    {
        if (providerCode == null || providerCode.isBlank())
        {
            return false;
        }
        return "minimax".equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public boolean supportsModel(String modelName)
    {
        if (AudioProviderClient.super.supportsModel(modelName))
        {
            return true;
        }
        String normalized = StrUtil.nullToEmpty(modelName).toLowerCase();
        // 命中 "speech-" 前缀，且不是豆包的 seed-tts / seed-icl（豆包走 VolcengineTtsProviderClient）
        return normalized.startsWith(MinimaxTtsConstants.MODEL_HINT_SPEECH);
    }
    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaAudioGenerateRequest request)
    {
        validateAuth(modelConfig);
        validateRequest(request);

        Map<String, Object> body = buildSubmitBody(modelConfig, request);
        String json = JSONUtil.toJsonStr(body);
        // 统一改走非流式同步接口 /v1/t2a_v2
        String url = joinUrl(modelConfig.getBaseUrl(), MinimaxTtsConstants.T2A_SYNC_PATH);
        // 音频格式：决定 OSS 落库后缀（默认 mp3）
        String format = StrUtil.isNotBlank(request.getAudioFormat())
                ? request.getAudioFormat() : MinimaxTtsConstants.DEFAULT_AUDIO_FORMAT;

        log.info("MinimaxTts 同步合成, model={}, voiceId={}, textLen={}, previewMode={}",
                modelConfig.getModelCode(), request.getVoiceCode(),
                StrUtil.length(request.getTtsText()), request.isPreviewMode());

        String raw;
        try
        {
            raw = doPost(url, modelConfig, json);
        }
        catch (Exception ex)
        {
            log.error("MinimaxTts 同步合成网络异常, model={}, voiceId={}",
                    modelConfig.getModelCode(), request.getVoiceCode(), ex);
            return ProviderSubmitResult.builder().rawResponse(ex.getMessage()).build();
        }

        JsonNode root = ProviderResponseHelper.readTree(raw);
        Integer code = ProviderResponseHelper.readInt(root, MinimaxTtsConstants.RESP_BASE_STATUS_CODE);
        if (Objects.isNull(code) || code != MinimaxTtsConstants.VENDOR_CODE_OK)
        {
            String message = ProviderResponseHelper.readText(root, MinimaxTtsConstants.RESP_BASE_STATUS_MSG);
            log.error("MinimaxTts 同步合成失败, code={}, message={}, raw={}", code, message,
                    StrUtil.brief(raw, MinimaxTtsConstants.LOG_TEXT_ABBREV_MAX));
            return ProviderSubmitResult.builder().rawResponse(raw).build();
        }

        // data.audio 为 hex 编码音频，解码为字节
        String audioHex = ProviderResponseHelper.readText(root, MinimaxTtsConstants.RESP_AUDIO);
        if (StrUtil.isBlank(audioHex))
        {
            log.error("MinimaxTts 同步合成未返回音频, model={}, raw={}", modelConfig.getModelCode(),
                    StrUtil.brief(raw, MinimaxTtsConstants.LOG_TEXT_ABBREV_MAX));
            return ProviderSubmitResult.builder().rawResponse(MinimaxTtsConstants.ERR_TTS_NO_AUDIO).build();
        }
        byte[] audioBytes;
        try
        {
            audioBytes = HexUtil.decodeHex(audioHex);
        }
        catch (Exception ex)
        {
            log.error("MinimaxTts 音频 hex 解码失败, model={}, hexLen={}",
                    modelConfig.getModelCode(), StrUtil.length(audioHex), ex);
            return ProviderSubmitResult.builder().rawResponse(MinimaxTtsConstants.ERR_TTS_SUBMIT).build();
        }
        if (audioBytes.length == 0)
        {
            log.error("MinimaxTts 音频解码为空, model={}", modelConfig.getModelCode());
            return ProviderSubmitResult.builder().rawResponse(MinimaxTtsConstants.ERR_TTS_NO_AUDIO).build();
        }

        // 音频时长（毫秒）：官方 extra_info.audio_length，缺失为 null（不估算）
        Long audioDurationMs = readAudioDurationMs(root);

        // 试听模式：直接回 base64、不上传对象存储（试听免费、不落库）
        if (request.isPreviewMode())
        {
            String audioBase64 = Base64.getEncoder().encodeToString(audioBytes);
            log.info("MinimaxTts 试听合成成功(base64, 不落库), model={}, audioBytes={}, durationMs={}",
                    modelConfig.getModelCode(), audioBytes.length, audioDurationMs);
            return ProviderSubmitResult.builder()
                    .audioBase64(audioBase64)
                    .audioDurationMs(audioDurationMs)
                    .build();
        }

        // 正式任务：音频字节仅在内存中处理并上传 OSS，任务只保存 URL（submit 即终态、不轮询）。
        String ossUrl = uploadAudioToOss(audioBytes, format);
        if (StrUtil.isBlank(ossUrl))
        {
            log.error("MinimaxTts 音频上传 OSS 失败, model={}, bytes={}",
                    modelConfig.getModelCode(), audioBytes.length);
            return ProviderSubmitResult.builder().rawResponse(MinimaxTtsConstants.ERR_TTS_SUBMIT).build();
        }
        log.info("MinimaxTts 同步合成成功(hex→OSS), model={}, audioBytes={}, durationMs={}",
                modelConfig.getModelCode(), audioBytes.length, audioDurationMs);
        return ProviderSubmitResult.builder()
                .ossUrl(ossUrl)
                .audioDurationMs(audioDurationMs)
                .build();
    }
    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId)
    {
        // 非流式同步：submit 已落 ossUrl、不回填 providerTaskId，调度层不会轮询本方法。
        log.error("MinimaxTts query 不应被调用（同步模型）, providerTaskId={}", providerTaskId);
        return ProviderTaskResult.builder()
                .status(MinimaxTtsConstants.TASK_STATUS_FAILED)
                .errorMessage(MinimaxTtsConstants.ERR_TTS_QUERY).build();
    }

    /**
     * 解析合成音频时长（毫秒）：官方 {@code extra_info.audio_length}。
     * 缺失 / 非法值返回 null（不做码率估算，避免不同 format 下失真）。
     */
    private Long readAudioDurationMs(JsonNode root)
    {
        Integer ms = ProviderResponseHelper.readInt(root, MinimaxTtsConstants.RESP_EXTRA_AUDIO_LENGTH);
        if (Objects.isNull(ms) || ms <= 0)
        {
            return null;
        }
        return ms.longValue();
    }
    private Map<String, Object> buildSubmitBody(AiModelConfigVo modelConfig, MediaAudioGenerateRequest request)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        // 真实上游模型名：real_model_code 优先，为空回退 model_code；仍空用默认
        String upstreamModel = com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(modelConfig, null);
        body.put(MinimaxTtsConstants.FIELD_MODEL,
                StrUtil.isNotBlank(upstreamModel)
                        ? upstreamModel : MinimaxTtsConstants.DEFAULT_MODEL);
        body.put(MinimaxTtsConstants.FIELD_TEXT, request.getTtsText());

        // 非流式同步：stream=false + output_format=hex（本地解码后落 OSS / 回 base64）
        body.put(MinimaxTtsConstants.FIELD_STREAM, Boolean.FALSE);
        body.put(MinimaxTtsConstants.FIELD_OUTPUT_FORMAT, MinimaxTtsConstants.OUTPUT_FORMAT_HEX);

        // language_boost：options.languageBoost 优先；空 → auto（按官方推荐默认）
        String languageBoost = MapUtil.getStr(request.getOptions(), MinimaxTtsConstants.OPTIONS_LANGUAGE_BOOST);
        if (StrUtil.isBlank(languageBoost))
        {
            languageBoost = "auto";
        }
        body.put(MinimaxTtsConstants.FIELD_LANGUAGE_BOOST, languageBoost);

        // aigc_watermark：options.aigcWatermark
        Boolean aigc = MapUtil.getBool(request.getOptions(), MinimaxTtsConstants.OPTIONS_AIGC_WATERMARK);
        if (Boolean.TRUE.equals(aigc))
        {
            body.put(MinimaxTtsConstants.FIELD_AIGC_WATERMARK, Boolean.TRUE);
        }

        body.put(MinimaxTtsConstants.FIELD_VOICE_SETTING, buildVoiceSetting(request));
        body.put(MinimaxTtsConstants.FIELD_AUDIO_SETTING, buildAudioSetting(request));

        Map<String, Object> pronunciation = buildPronunciationDict(request);
        if (CollectionUtil.isNotEmpty(pronunciation))
        {
            body.put(MinimaxTtsConstants.FIELD_PRONUNCIATION_DICT, pronunciation);
        }

        Map<String, Object> modify = buildVoiceModify(request);
        if (CollectionUtil.isNotEmpty(modify))
        {
            body.put(MinimaxTtsConstants.FIELD_VOICE_MODIFY, modify);
        }
        return body;
    }

    private Map<String, Object> buildVoiceSetting(MediaAudioGenerateRequest request)
    {
        Map<String, Object> vs = new LinkedHashMap<>();
        vs.put(MinimaxTtsConstants.FIELD_VOICE_ID, request.getVoiceCode());
        vs.put(MinimaxTtsConstants.FIELD_SPEED, mapSpeed(request.getSpeechRate()));
        vs.put(MinimaxTtsConstants.FIELD_VOL, mapVolume(request.getLoudnessRate()));
        vs.put(MinimaxTtsConstants.FIELD_PITCH, mapPitch(request.getPitch()));
        if (StrUtil.isNotBlank(request.getEmotion()))
        {
            vs.put(MinimaxTtsConstants.FIELD_EMOTION, request.getEmotion());
        }
        Boolean enNorm = MapUtil.getBool(request.getOptions(), MinimaxTtsConstants.OPTIONS_ENGLISH_NORMALIZATION);
        if (Boolean.TRUE.equals(enNorm))
        {
            vs.put(MinimaxTtsConstants.FIELD_ENGLISH_NORMALIZATION, Boolean.TRUE);
        }
        return vs;
    }

    private Map<String, Object> buildAudioSetting(MediaAudioGenerateRequest request)
    {
        Map<String, Object> as = new LinkedHashMap<>();
        as.put(MinimaxTtsConstants.FIELD_AUDIO_SAMPLE_RATE,
                Objects.nonNull(request.getSampleRate())
                        ? request.getSampleRate() : MinimaxTtsConstants.DEFAULT_SAMPLE_RATE);
        Integer bitrate = MapUtil.getInt(request.getOptions(), MinimaxTtsConstants.OPTIONS_BITRATE);
        as.put(MinimaxTtsConstants.FIELD_BITRATE,
                Objects.nonNull(bitrate) ? bitrate : MinimaxTtsConstants.DEFAULT_BITRATE);
        as.put(MinimaxTtsConstants.FIELD_FORMAT,
                StrUtil.isNotBlank(request.getAudioFormat())
                        ? request.getAudioFormat() : MinimaxTtsConstants.DEFAULT_AUDIO_FORMAT);
        Integer channel = MapUtil.getInt(request.getOptions(), MinimaxTtsConstants.OPTIONS_CHANNEL);
        as.put(MinimaxTtsConstants.FIELD_CHANNEL,
                Objects.nonNull(channel) ? channel : MinimaxTtsConstants.DEFAULT_CHANNEL);
        return as;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildPronunciationDict(MediaAudioGenerateRequest request)
    {
        Map<String, Object> options = request.getOptions();
        if (CollectionUtil.isEmpty(options))
        {
            return null;
        }
        Object raw = options.get(MinimaxTtsConstants.OPTIONS_PRONUNCIATION_TONE);
        if (!(raw instanceof List))
        {
            return null;
        }
        List<String> tone = (List<String>) raw;
        if (CollectionUtil.isEmpty(tone))
        {
            return null;
        }
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put(MinimaxTtsConstants.FIELD_TONE, tone);
        return dict;
    }

    private Map<String, Object> buildVoiceModify(MediaAudioGenerateRequest request)
    {
        Map<String, Object> options = request.getOptions();
        if (CollectionUtil.isEmpty(options))
        {
            return null;
        }
        Map<String, Object> modify = new LinkedHashMap<>();
        String soundEffect = MapUtil.getStr(options, MinimaxTtsConstants.OPTIONS_SOUND_EFFECT);
        if (StrUtil.isNotBlank(soundEffect))
        {
            modify.put(MinimaxTtsConstants.FIELD_VOICE_MODIFY_SOUND_EFFECTS, soundEffect);
        }
        Integer intensity = MapUtil.getInt(options, MinimaxTtsConstants.OPTIONS_VOICE_MODIFY_INTENSITY);
        if (Objects.nonNull(intensity))
        {
            modify.put(MinimaxTtsConstants.FIELD_VOICE_MODIFY_INTENSITY, clamp(intensity, -100, 100));
        }
        Integer timbre = MapUtil.getInt(options, MinimaxTtsConstants.OPTIONS_VOICE_MODIFY_TIMBRE);
        if (Objects.nonNull(timbre))
        {
            modify.put(MinimaxTtsConstants.FIELD_VOICE_MODIFY_TIMBRE, clamp(timbre, -100, 100));
        }
        // voice_modify.pitch 与 voice_setting.pitch 语义不同：前者是 [-100,100] 音高调整，走 options 显式传
        Integer modifyPitch = MapUtil.getInt(options, "voiceModifyPitch");
        if (Objects.nonNull(modifyPitch))
        {
            modify.put(MinimaxTtsConstants.FIELD_VOICE_MODIFY_PITCH, clamp(modifyPitch, -100, 100));
        }
        return modify;
    }
    /**
     * 语速：请求里是豆包系 {@code [-50, 100]}（0 = 1.0x，-50 = 0.5x，100 = 2.0x），
     * MiniMax 要 {@code [0.5, 2.0]} 的乘数，这里做线性映射。
     */
    private double mapSpeed(Integer rate)
    {
        if (Objects.isNull(rate))
        {
            return 1.0;
        }
        double mul;
        if (rate >= 0)
        {
            // 0 → 1.0, 100 → 2.0
            mul = 1.0 + (rate / 100.0);
        }
        else
        {
            // 0 → 1.0, -50 → 0.5
            mul = 1.0 + (rate / 100.0);
        }
        return clampDouble(mul, MinimaxTtsConstants.MINIMAX_SPEED_MIN, MinimaxTtsConstants.MINIMAX_SPEED_MAX);
    }

    /**
     * 音量：请求里是豆包系 {@code [-50, 100]}（0 = 1.0x），MiniMax 要 {@code (0, 10]}。
     * 映射规则：0 → 1.0，100 → 10，-50 → 0.5；线性按正负分段。
     */
    private double mapVolume(Integer rate)
    {
        if (Objects.isNull(rate))
        {
            return 1.0;
        }
        double mul;
        if (rate >= 0)
        {
            // 0 → 1.0, 100 → 10.0
            mul = 1.0 + (rate * 0.09);
        }
        else
        {
            // 0 → 1.0, -50 → 0.5；下限保护在 MINIMAX_VOL_MIN 以上
            mul = 1.0 + (rate / 100.0);
        }
        return clampDouble(mul, MinimaxTtsConstants.MINIMAX_VOL_MIN, MinimaxTtsConstants.MINIMAX_VOL_MAX);
    }

    private int mapPitch(Integer pitch)
    {
        if (Objects.isNull(pitch))
        {
            return 0;
        }
        return clamp(pitch, MinimaxTtsConstants.PITCH_MIN, MinimaxTtsConstants.PITCH_MAX);
    }
    /**
     * 将完整音频字节上传 OSS，返回系统可访问 URL。
     *
     * @param audioBytes 音频字节
     * @param format     音频格式（mp3 / wav / pcm / flac / opus）
     * @return OSS URL；失败返回 null
     */
    private String uploadAudioToOss(byte[] audioBytes, String format)
    {
        try
        {
            String fmt = StrUtil.isNotBlank(format)
                    ? format.trim().toLowerCase() : MinimaxTtsConstants.DEFAULT_AUDIO_FORMAT;
            String suffix;
            String contentType;
            switch (fmt)
            {
                case "wav":
                case "pcmu_wav":
                    suffix = MinimaxTtsConstants.AUDIO_SUFFIX_WAV;
                    contentType = MinimaxTtsConstants.AUDIO_CONTENT_TYPE_WAV;
                    break;
                case "pcm":
                case "pcmu_raw":
                    suffix = MinimaxTtsConstants.AUDIO_SUFFIX_PCM;
                    contentType = MinimaxTtsConstants.AUDIO_CONTENT_TYPE_PCM;
                    break;
                case "flac":
                    suffix = MinimaxTtsConstants.AUDIO_SUFFIX_FLAC;
                    contentType = MinimaxTtsConstants.AUDIO_CONTENT_TYPE_FLAC;
                    break;
                case "opus":
                    suffix = MinimaxTtsConstants.AUDIO_SUFFIX_OPUS;
                    contentType = MinimaxTtsConstants.AUDIO_CONTENT_TYPE_OPUS;
                    break;
                default:
                    suffix = MinimaxTtsConstants.AUDIO_SUFFIX_MP3;
                    contentType = MinimaxTtsConstants.AUDIO_CONTENT_TYPE_MP3;
            }
            UploadResult uploadResult = OssFactory.instance().uploadSuffix(audioBytes, suffix, contentType);
            return uploadResult.getUrl();
        }
        catch (Exception e)
        {
            log.error("MinimaxTts 音频 OSS 上传异常, error={}", e.getMessage(), e);
            return null;
        }
    }
    private String doPost(String url, AiModelConfigVo modelConfig, String json)
    {
        ModelIoDump.req(url, json); // 【临时调试】记录下发上游入参
        try (HttpResponse response = HttpRequest.post(url)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .header("Authorization", "Bearer " + modelConfig.getApiKey())
                .body(json)
                .timeout(MinimaxTtsConstants.HTTP_TIMEOUT_MS)
                .execute())
        {
            return ModelIoDump.resp(url, response.body()); // 【临时调试】记录上游出参
        }
    }

    private void validateAuth(AiModelConfigVo modelConfig)
    {
        if (Objects.isNull(modelConfig) || StrUtil.isBlank(modelConfig.getApiKey()))
        {
            throw new IllegalStateException(MinimaxTtsConstants.ERR_APP_KEY_EMPTY);
        }
        if (StrUtil.isBlank(modelConfig.getBaseUrl()))
        {
            throw new IllegalStateException(MinimaxTtsConstants.ERR_APP_KEY_EMPTY);
        }
    }

    private void validateRequest(MediaAudioGenerateRequest request)
    {
        if (Objects.isNull(request) || StrUtil.isBlank(request.getTtsText()))
        {
            throw new IllegalArgumentException(MinimaxTtsConstants.ERR_TEXT_EMPTY);
        }
        if (StrUtil.isBlank(request.getVoiceCode()))
        {
            throw new IllegalArgumentException(MinimaxTtsConstants.ERR_VOICE_EMPTY);
        }
    }

    private String joinUrl(String base, String path)
    {
        if (StrUtil.isBlank(base))
        {
            throw new IllegalStateException(MinimaxTtsConstants.ERR_APP_KEY_EMPTY);
        }
        String trimmed = base.trim();
        if (trimmed.endsWith("/"))
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + path;
    }

    private int clamp(int value, int min, int max)
    {
        if (value < min) return min;
        return Math.min(value, max);
    }

    private double clampDouble(double value, double min, double max)
    {
        if (value < min) return min;
        if (value > max) return max;
        // 保留 2 位小数避免浮点尾数
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
