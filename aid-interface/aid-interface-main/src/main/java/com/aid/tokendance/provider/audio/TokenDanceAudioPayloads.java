package com.aid.tokendance.provider.audio;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.media.util.WaveAudioAssembler;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** TokenDance 音频协议字段映射与 SSE 解码；不复用其他供应商的默认鉴权和路由。 */
public final class TokenDanceAudioPayloads {
    private static final Set<String> MINIMAX_FORMATS = Set.of("mp3", "wav", "flac");
    private static final Set<String> ARK_FORMATS = Set.of("mp3", "wav", "pcm", "ogg_opus");
    private static final int MAX_AUDIO_BYTES = 64 * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TokenDanceAudioPayloads() { }

    public static void validate(AiModelConfigVo config, MediaAudioGenerateRequest request) {
        if (!"tokendance".equalsIgnoreCase(config.getProviderCode())) return;
        String protocol = config.getProtocol();
        if (request.isPreviewMode()) throw new ServiceException("请创建试听任务");
        if ("tokendance:openai:chat-completions".equals(protocol)) {
            TokenDanceMimoAudioSupport.validate(config, request);
            return;
        }
        if ("tokendance:minimax:voice_clone".equals(protocol)) {
            validateClone(config, request);
            return;
        }
        if ("tokendance:minimax:t2a_v2_ws".equals(protocol) || "tokendance:ark:tts_ws".equals(protocol)) {
            TokenDanceWebSocketAudioSupport.validate(config, request);
            return;
        }
        boolean minimax = "tokendance:minimax:t2a_v2".equals(protocol) || "tokendance:minimax:t2a_v2_ws".equals(protocol);
        boolean ark = "tokendance:ark:tts".equals(protocol) || "tokendance:ark:tts_ws".equals(protocol);
        if (!minimax && !ark) throw new ServiceException("配音协议未核验");
        String format = StrUtil.blankToDefault(request.getAudioFormat(), "mp3");
        if (!(minimax ? MINIMAX_FORMATS : ARK_FORMATS).contains(format)) throw new ServiceException("音频格式不支持");
        if (StrUtil.isBlank(request.getTtsText()) || StrUtil.isBlank(request.getVoiceCode())) throw new ServiceException("配音参数不完整");
        if (minimax && request.getTtsText().codePointCount(0, request.getTtsText().length()) >= 10000) {
            throw new ServiceException("配音文本过长");
        }
        if (minimax && request.getSampleRate() != null
                && !Set.of(8000, 16000, 22050, 24000, 32000, 44100).contains(request.getSampleRate())) {
            throw new ServiceException("采样率不支持");
        }
        // 已存在的正式任务校验还会校验语速、音量、音调与采样率；Provider 重复调用时同样保护。
        range(request.getSpeechRate(), -50, 100, "语速无效");
        range(request.getLoudnessRate(), -50, 100, "音量无效");
        range(request.getPitch(), -12, 12, "音调无效");
        if (minimax && request.getEmotionScale() != null) throw new ServiceException("情感强度不支持");
        if (minimax && StrUtil.isNotBlank(request.getEmotion())) {
            String model = ModelCodeResolver.resolveUpstreamModel(config, request.getModelName());
            Set<String> emotions = Set.of("happy", "sad", "angry", "fearful", "disgusted", "surprised", "calm", "fluent", "whisper");
            if (!emotions.contains(request.getEmotion()) || model.contains("2.8") && "whisper".equals(request.getEmotion())) {
                throw new ServiceException("音色情感不支持");
            }
        }
        if (ark && StrUtil.isNotBlank(request.getEmotion())) {
            // 声音情感受具体音色控制；不能在未核验时假装按通用字段发送。
            throw new ServiceException("音色情感未核验");
        }
        if (ark && request.getEmotionScale() != null) throw new ServiceException("音色情感未核验");
        if (ark && request.getSampleRate() != null
                && !Set.of(8000, 16000, 22050, 24000, 32000, 44100, 48000).contains(request.getSampleRate())) {
            throw new ServiceException("采样率不支持");
        }
        if (ark && Boolean.TRUE.equals(request.getEnableTimestamp())) throw new ServiceException("字幕回传尚未接通");
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            Set<String> allowed = minimax ? Set.of("channel", "bitrate", "languageBoost", "aigcWatermark", "subtitleType",
                    "pronunciationTone", "textNormalization", "englishNormalization", "latexRead", "voiceModifyPitch", "voiceModifyIntensity", "voiceModifyTimbre", "soundEffect")
                    : Set.of("silenceDurationMs");
            TokenDancePayloadSupport.rejectUnknownRequestOptions(config, options, allowed);
            for (String key : java.util.List.of("aigcWatermark", "textNormalization", "englishNormalization", "latexRead")) {
                if (options.get(key) != null && !(options.get(key) instanceof Boolean)) throw new ServiceException("音频开关无效");
            }
            if (minimax) {
                if (options.get("englishNormalization") != null && options.get("textNormalization") != null
                        && !options.get("englishNormalization").equals(options.get("textNormalization"))) {
                    throw new ServiceException("文本规范化冲突");
                }
                optionNumber(request, "channel", 1, Set.of(1, 2));
                optionNumber(request, "bitrate", 128000, Set.of(32000, 64000, 128000, 256000));
                if (options.get("subtitleType") != null && !Set.of("sentence", "word").contains(String.valueOf(options.get("subtitleType")))) {
                    throw new ServiceException("字幕类型不支持");
                }
                pronunciation(request);
                voiceModify(request);
            }
            if (ark && options.get("silenceDurationMs") != null) {
                try { range(Integer.valueOf(String.valueOf(options.get("silenceDurationMs"))), 0, 30000, "静音时长无效"); }
                catch (NumberFormatException ex) { throw new ServiceException("静音时长无效"); }
            }
        }
    }

    private static void validateClone(AiModelConfigVo config, MediaAudioGenerateRequest request) {
        if (request.getVoiceCode() == null || !request.getVoiceCode().matches("[A-Za-z][A-Za-z0-9_-]{6,126}[A-Za-z0-9]")) {
            throw new ServiceException("音色编码无效");
        }
        if (StrUtil.isBlank(request.getTtsText()) || request.getTtsText().codePointCount(0, request.getTtsText().length()) > 1000) {
            throw new ServiceException("试听文本超限");
        }
        Map<String, Object> options = request.getOptions();
        if (options == null || !(options.get("cloneFileId") instanceof String fileId)
                || !fileId.matches("[A-Za-z0-9_-]{1,128}")) throw new ServiceException("克隆样本无效");
        TokenDancePayloadSupport.rejectUnknownRequestOptions(config, options, Set.of("cloneFileId"));
        if (request.getSpeechRate() != null || request.getLoudnessRate() != null || request.getPitch() != null
                || request.getEmotion() != null || request.getEmotionScale() != null || request.getSampleRate() != null
                || Boolean.TRUE.equals(request.getEnableTimestamp())
                || (StrUtil.isNotBlank(request.getAudioFormat()) && !"mp3".equals(request.getAudioFormat()))) {
            throw new ServiceException("克隆参数不支持");
        }
    }

    public static Map<String, Object> cloneVoice(AiModelConfigVo config, MediaAudioGenerateRequest request) {
        validate(config, request);
        return com.aid.model.definition.ModelConfiguredRequestBody.apply(config, Map.of("model", ModelCodeResolver.resolveUpstreamModel(config, request.getModelName()),
                "voice_id", request.getVoiceCode(), "file_id", request.getOptions().get("cloneFileId"), "text", request.getTtsText()), request);
    }

    public static Map<String, Object> minimax(AiModelConfigVo config, MediaAudioGenerateRequest request) {
        validate(config, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", ModelCodeResolver.resolveUpstreamModel(config, request.getModelName()));
        body.put("text", request.getTtsText());
        body.put("stream", false);
        body.put("output_format", "url");
        Map<String, Object> voice = new LinkedHashMap<>();
        voice.put("voice_id", request.getVoiceCode());
        voice.put("speed", multiplier(request.getSpeechRate()));
        voice.put("vol", multiplier(request.getLoudnessRate()));
        voice.put("pitch", request.getPitch() == null ? 0 : request.getPitch());
        if (StrUtil.isNotBlank(request.getEmotion())) voice.put("emotion", request.getEmotion());
        // 兼容现有业务字段名称；当前官方字段同时控制中文和英文规范化。
        copyOption(request, voice, "englishNormalization", "text_normalization");
        copyOption(request, voice, "textNormalization", "text_normalization");
        copyOption(request, voice, "latexRead", "latex_read");
        body.put("voice_setting", voice);
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("sample_rate", request.getSampleRate() == null ? 32000 : request.getSampleRate());
        audio.put("format", StrUtil.blankToDefault(request.getAudioFormat(), "mp3"));
        audio.put("channel", optionNumber(request, "channel", 1, Set.of(1, 2)));
        audio.put("bitrate", optionNumber(request, "bitrate", 128000, Set.of(32000, 64000, 128000, 256000)));
        body.put("audio_setting", audio);
        if (Boolean.TRUE.equals(request.getEnableTimestamp())) body.put("subtitle_enable", true);
        copyOption(request, body, "languageBoost", "language_boost");
        copyOption(request, body, "aigcWatermark", "aigc_watermark");
        copyOption(request, body, "subtitleType", "subtitle_type");
        Object pronunciation = pronunciation(request);
        if (pronunciation != null) body.put("pronunciation_dict", Map.of("tone", pronunciation));
        Map<String, Object> modify = voiceModify(request);
        if (!modify.isEmpty()) body.put("voice_modify", modify);
        return com.aid.model.definition.ModelConfiguredRequestBody.apply(config, body, request);
    }

    public static Map<String, Object> ark(AiModelConfigVo config, MediaAudioGenerateRequest request) {
        validate(config, request);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("text", request.getTtsText());
        params.put("speaker", request.getVoiceCode());
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("format", StrUtil.blankToDefault(request.getAudioFormat(), "mp3"));
        audio.put("sample_rate", request.getSampleRate() == null ? 24000 : request.getSampleRate());
        if (request.getSpeechRate() != null) audio.put("speech_rate", request.getSpeechRate());
        if (request.getLoudnessRate() != null) audio.put("loudness_rate", request.getLoudnessRate());
        params.put("audio_params", audio);
        Map<String, Object> additions = new LinkedHashMap<>();
        if (request.getPitch() != null) additions.put("post_process", Map.of("pitch", request.getPitch()));
        if (request.getOptions() != null && request.getOptions().get("silenceDurationMs") != null) {
            try {
                int silence = Integer.parseInt(String.valueOf(request.getOptions().get("silenceDurationMs")));
                range(silence, 0, 30000, "静音时长无效");
                additions.put("silence_duration", silence);
            } catch (NumberFormatException ex) { throw new ServiceException("静音时长无效"); }
        }
        if (!additions.isEmpty()) params.put("additions", stringify(additions));
        return com.aid.model.definition.ModelConfiguredRequestBody.apply(config, Map.of("req_params", params), request);
    }

    /** SSE 必须包含官方成功终态；断流即使收到音频也不能作为成功。 */
    public static DecodedAudio decodeArkSse(String response) {
        WaveAudioAssembler audio = new WaveAudioAssembler();
        boolean completed = false;
        JsonNode terminal = MAPPER.createObjectNode();
        try {
            StringBuilder event = new StringBuilder();
            String normalized = response == null ? "" : response.replace("\r\n", "\n");
            for (String line : (normalized + "\n\n").split("\n", -1)) {
                if (line.startsWith("data:")) {
                    if (!event.isEmpty()) event.append('\n');
                    event.append(line.substring(5).stripLeading());
                } else if (line.isEmpty() && !event.isEmpty()) {
                    JsonNode frame = MAPPER.readTree(event.toString());
                    event.setLength(0);
                    int code = frame.path("code").asInt(-1);
                    if (code == 20000000) { completed = true; terminal = frame; break; }
                    if (code != 0) throw new ServiceException("语音合成失败");
                    String encoded = frame.path("data").asText();
                    if (!encoded.isEmpty()) {
                        if (encoded.length() > MAX_AUDIO_BYTES * 2L) throw new ServiceException("音频响应过大");
                        byte[] chunk = Base64.getDecoder().decode(encoded);
                        if (audio.size() + (long) chunk.length > MAX_AUDIO_BYTES) throw new ServiceException("音频响应过大");
                        audio.append(chunk);
                    }
                }
            }
            if (!completed || audio.size() == 0) throw new ServiceException("音频响应未完成");
            return new DecodedAudio(audio.finish(), terminal);
        } catch (ServiceException ex) { throw ex; }
        catch (Exception ex) { throw new ServiceException("音频响应无效"); }
    }

    public record DecodedAudio(byte[] bytes, JsonNode terminal) {
        @Override public String toString() { return "DecodedAudio[bytes=" + bytes.length + "]"; }
    }

    private static BigDecimal multiplier(Integer value) {
        return BigDecimal.ONE.add(BigDecimal.valueOf(value == null ? 0 : value).divide(BigDecimal.valueOf(100)));
    }

    private static void range(Integer value, int min, int max, String message) {
        if (value != null && (value < min || value > max)) throw new ServiceException(message);
    }

    private static int optionNumber(MediaAudioGenerateRequest request, String key, int fallback, Set<Integer> allowed) {
        Object raw = request.getOptions() == null ? null : request.getOptions().get(key);
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(String.valueOf(raw));
            if (!allowed.contains(value)) throw new ServiceException("音频参数无效");
            return value;
        } catch (NumberFormatException ex) { throw new ServiceException("音频参数无效"); }
    }

    private static void copyOption(MediaAudioGenerateRequest request, Map<String, Object> body, String key, String upstream) {
        Object value = request.getOptions() == null ? null : request.getOptions().get(key);
        if (value != null) body.put(upstream, value);
    }

    private static String stringify(Object object) {
        try { return MAPPER.writeValueAsString(object); }
        catch (Exception ex) { throw new ServiceException("音频参数无效"); }
    }

    private static Object pronunciation(MediaAudioGenerateRequest request) {
        Object raw = request.getOptions() == null ? null : request.getOptions().get("pronunciationTone");
        if (raw == null) return null;
        if (!(raw instanceof java.util.List<?> rules) || rules.isEmpty() || rules.size() > 100
                || rules.stream().anyMatch(value -> !(value instanceof String text)
                || text.isBlank() || text.length() > 256)) {
            throw new ServiceException("发音规则无效");
        }
        return raw;
    }

    private static Map<String, Object> voiceModify(MediaAudioGenerateRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> options = request.getOptions();
        if (options == null) return result;
        for (String name : java.util.List.of("Pitch", "Intensity", "Timbre")) {
            Object raw = options.get("voiceModify" + name);
            if (raw == null) continue;
            try {
                int value = Integer.parseInt(String.valueOf(raw));
                range(value, -100, 100, "音效参数无效");
                result.put(name.toLowerCase(java.util.Locale.ROOT), value);
            } catch (NumberFormatException ex) { throw new ServiceException("音效参数无效"); }
        }
        Object effect = options.get("soundEffect");
        if (effect != null) {
            if (!Set.of("spacious_echo", "auditorium_echo", "lofi_telephone", "robotic").contains(String.valueOf(effect))) {
                throw new ServiceException("音效类型不支持");
            }
            result.put("sound_effects", effect);
        }
        return result;
    }
}
