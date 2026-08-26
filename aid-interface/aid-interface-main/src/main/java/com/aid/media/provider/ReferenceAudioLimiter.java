package com.aid.media.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.ReferenceAudioInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 参考音频能力与「数量上限」统一收口：capability_json 的参考音频键位只在此解析，
 * 超限按顺序截断并打 warn，与 {@link ReferenceImageLimiter} 口径一致，不抛超限异常。
 *
 * @author 视觉AID
 */
@Slf4j
public final class ReferenceAudioLimiter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** capability_json 键：是否支持参考音频 */
    public static final String KEY_SUPPORTS_REFERENCE_AUDIO = "supportsReferenceAudio";

    /** capability_json 键：参考音频条数上限 */
    public static final String KEY_MAX_REFERENCE_AUDIOS = "maxReferenceAudios";

    /** capability_json 键：单条最短时长（秒） */
    public static final String KEY_MIN_DURATION_SECONDS = "referenceAudioMinDurationSeconds";

    /** capability_json 键：单条最长时长（秒） */
    public static final String KEY_MAX_DURATION_SECONDS = "referenceAudioMaxDurationSeconds";

    /** capability_json 键：多条总时长上限（秒） */
    public static final String KEY_MAX_TOTAL_DURATION_SECONDS = "referenceAudioMaxTotalDurationSeconds";

    /** capability_json 键：允许的音频格式 */
    public static final String KEY_REFERENCE_AUDIO_FORMATS = "referenceAudioFormats";

    /** 服务端可解析时长的音频格式；白名单超出此集合时无法探测时长 */
    private static final List<String> PROBEABLE_FORMATS = List.of("wav", "mp3");

    private ReferenceAudioLimiter() {
    }

    /**
     * 解析模型的参考音频能力配置。
     *
     * @param modelConfig 模型聚合配置（可空）
     * @return 能力配置；模型为空或未声明返回不可用配置，绝不返回 null
     */
    public static ReferenceAudioCapability readCapability(AiModelConfigVo modelConfig) {
        return readCapabilityJson(Objects.isNull(modelConfig) ? null : modelConfig.getCapabilityJson());
    }

    /**
     * 从 capability_json 文本解析参考音频能力配置。
     *
     * @param capabilityJson 能力 JSON 文本（可空）
     * @return 能力配置；缺失或解析失败返回不可用配置，绝不返回 null
     */
    public static ReferenceAudioCapability readCapabilityJson(String capabilityJson) {
        ReferenceAudioCapability capability = new ReferenceAudioCapability();
        if (StrUtil.isBlank(capabilityJson)) {
            return capability;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(capabilityJson);
        } catch (Exception ex) {
            log.warn("解析 capability_json 参考音频键位失败, err={}", ex.getMessage());
            return capability;
        }
        if (Objects.isNull(node) || !node.isObject()) {
            return capability;
        }
        capability.supported = node.path(KEY_SUPPORTS_REFERENCE_AUDIO).asBoolean(false);
        capability.maxCount = node.path(KEY_MAX_REFERENCE_AUDIOS).asInt(0);
        capability.minDurationSeconds = node.path(KEY_MIN_DURATION_SECONDS).asInt(0);
        capability.maxDurationSeconds = node.path(KEY_MAX_DURATION_SECONDS).asInt(0);
        capability.maxTotalDurationSeconds = node.path(KEY_MAX_TOTAL_DURATION_SECONDS).asInt(0);
        capability.formats = readFormats(node);
        return capability;
    }

    /**
     * 按模型配置的条数上限截断参考音频列表（统一入口）：超限保留前 N 条并打 warn，不抛错。
     * 截断后重排 {@code index} 为 1..N，保证下发编号连续。
     *
     * @param audios      有序参考音频列表
     * @param modelConfig 模型配置
     * @param providerTag 日志用厂商标识
     * @return 截断并重排编号后的列表；入参为空返回空列表
     */
    public static List<ReferenceAudioInput> limit(List<ReferenceAudioInput> audios,
                                                  AiModelConfigVo modelConfig, String providerTag) {
        if (CollectionUtil.isEmpty(audios)) {
            return new ArrayList<>();
        }
        int max = readCapability(modelConfig).getMaxCount();
        List<ReferenceAudioInput> result = new ArrayList<>(audios);
        if (max > 0 && result.size() > max) {
            log.warn("{} 参考音频超过上限按顺序截断: max={}, 实际={}, 仅保留前{}条",
                    providerTag, max, result.size(), max);
            result = new ArrayList<>(result.subList(0, max));
        }
        reindex(result);
        return result;
    }

    /**
     * 重排参考音频编号为 1..N（截断/剔除后保证编号连续）。
     *
     * @param audios 参考音频列表（原地修改）
     */
    public static void reindex(List<ReferenceAudioInput> audios) {
        if (CollectionUtil.isEmpty(audios)) {
            return;
        }
        int index = 1;
        for (ReferenceAudioInput audio : audios) {
            if (Objects.nonNull(audio)) {
                audio.setIndex(index++);
            }
        }
    }

    /**
     * 音频格式是否可被服务端解析出时长。
     *
     * @param format 音频格式（大小写不敏感）
     * @return 可解析返回 true
     */
    public static boolean isProbeableFormat(String format) {
        return StrUtil.isNotBlank(format)
                && PROBEABLE_FORMATS.contains(StrUtil.trim(format).toLowerCase(Locale.ROOT));
    }

    /**
     * 服务端可解析时长的格式集合（供后台配置校验提示使用）。
     *
     * @return 不可变格式集合
     */
    public static List<String> probeableFormats() {
        return PROBEABLE_FORMATS;
    }

    private static List<String> readFormats(JsonNode node) {
        List<String> formats = new ArrayList<>();
        JsonNode array = node.path(KEY_REFERENCE_AUDIO_FORMATS);
        if (!array.isArray()) {
            return formats;
        }
        for (JsonNode item : array) {
            String value = StrUtil.trimToNull(item.asText(null));
            if (StrUtil.isNotBlank(value)) {
                formats.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return formats;
    }

    /** 模型参考音频能力配置。 */
    @Getter
    public static final class ReferenceAudioCapability {

        /** 是否声明支持参考音频。 */
        private boolean supported;

        /** 条数上限；0=不支持，-1=厂商未声明上限，正整数=明确上限。 */
        private int maxCount;

        /** 单条最短时长秒；未配置为 0 表示不限。 */
        private int minDurationSeconds;

        /** 单条最长时长秒；未配置为 0 表示不限。 */
        private int maxDurationSeconds;

        /** 多条总时长上限秒；未配置为 0 表示不限。 */
        private int maxTotalDurationSeconds;

        /** 允许的音频格式（小写）；为空表示未配置。 */
        private List<String> formats = new ArrayList<>();

        /**
         * 能力是否完整可用：声明支持且条数上限与格式白名单均已配置。
         *
         * @return 完整可用返回 true
         */
        public boolean isUsable() {
            return supported && (maxCount == -1 || maxCount > 0)
                    && CollectionUtil.isNotEmpty(formats);
        }

        /**
         * 能力已声明但配置不完整（缺条数上限或格式白名单）。
         *
         * @return 配置不完整返回 true
         */
        public boolean isIncomplete() {
            return supported && !isUsable();
        }

        /**
         * 格式是否命中白名单。
         *
         * @param format 音频格式
         * @return 命中返回 true
         */
        public boolean acceptsFormat(String format) {
            if (CollectionUtil.isEmpty(formats)) {
                return false;
            }
            if (formats.contains("*")) {
                return true;
            }
            return StrUtil.isNotBlank(format)
                    && formats.contains(StrUtil.trim(format).toLowerCase(Locale.ROOT));
        }

        /**
         * 单条时长是否落在允许区间。
         *
         * @param durationMs 时长毫秒
         * @return 合规返回 true
         */
        public boolean acceptsDuration(Integer durationMs) {
            if (Objects.isNull(durationMs) || durationMs <= 0) {
                return false;
            }
            if (minDurationSeconds > 0 && durationMs < minDurationSeconds * 1000L) {
                return false;
            }
            return maxDurationSeconds <= 0 || durationMs <= maxDurationSeconds * 1000L;
        }
    }
}
