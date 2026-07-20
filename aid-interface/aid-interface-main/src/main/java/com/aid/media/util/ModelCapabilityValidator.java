package com.aid.media.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 模型能力参数统一校验器：按 {@code aid_ai_model.capability_json} 声明的白名单
 * 校验前端透传的清晰度档位 / 画面比例 / 视频时长，防止不合法参数直达厂商造成调用失败。
 *
 * <p>校验语义与 TTS 情感白名单（{@code VoiceEmotionCapability}）对齐：
 * 供应商声明（capability_json）是唯一标准；<strong>白名单缺失/为空 = 模型未声明该能力，不拦截</strong>；
 * 白名单非空则严格命中（归一化后比对），未命中先 log 再抛短文案。
 * 入参为空（业务未传该参数）不校验，默认值由业务/Provider 各自兜底。</p>
 *
 * @author 视觉AID
 */
@Slf4j
public final class ModelCapabilityValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** capability_json 键：清晰度档位白名单 */
    private static final String KEY_SIZE_OPTIONS = "sizeOptions";

    /** capability_json 键：画面比例白名单 */
    private static final String KEY_ASPECT_RATIO_OPTIONS = "aspectRatioOptions";

    /** capability_json 键：视频时长白名单（秒） */
    private static final String KEY_DURATION_OPTIONS = "durationOptions";

    /** options 中承载清晰度档位的候选键（各链路历史写法不一，统一在此收口） */
    private static final String[] OPTION_SIZE_KEYS = {"resolution", "imageSize", "size"};

    /** options 中承载画面比例的候选键 */
    private static final String[] OPTION_RATIO_KEYS = {"aspect_ratio", "aspectRatio"};

    private ModelCapabilityValidator() {
    }

    /**
     * 校验图片生成参数（清晰度档位 + 画面比例）。
     *
     * @param modelConfig 模型聚合配置（含 capabilityJson，可空则不校验）
     * @param size        请求顶层 size（可空）
     * @param options     请求扩展参数（可空；从中提取 resolution/imageSize/aspect_ratio 等）
     */
    public static void validateImage(AiModelConfigVo modelConfig, String size, Map<String, Object> options) {
        if (Objects.isNull(modelConfig)) {
            return;
        }
        JsonNode capability = parseCapability(modelConfig.getCapabilityJson());
        if (Objects.isNull(capability)) {
            return;
        }
        // 清晰度：顶层 size 优先，其次 options 内历史键
        String effectiveSize = StrUtil.isNotBlank(size) ? size : readFirstText(options, OPTION_SIZE_KEYS);
        validateOption(capability, KEY_SIZE_OPTIONS, effectiveSize,
                modelConfig.getModelCode(), "清晰度不支持");
        String ratio = readFirstText(options, OPTION_RATIO_KEYS);
        validateOption(capability, KEY_ASPECT_RATIO_OPTIONS, ratio,
                modelConfig.getModelCode(), "画面比例不支持");
    }

    /**
     * 校验视频生成参数（清晰度档位 + 画面比例 + 时长）。
     *
     * @param modelConfig     模型聚合配置（含 capabilityJson，可空则不校验）
     * @param durationSeconds 请求时长秒（可空）
     * @param aspectRatio     请求顶层画面比例（可空）
     * @param options         请求扩展参数（可空；从中提取 resolution 等）
     */
    public static void validateVideo(AiModelConfigVo modelConfig, Integer durationSeconds,
                                     String aspectRatio, Map<String, Object> options) {
        if (Objects.isNull(modelConfig)) {
            return;
        }
        JsonNode capability = parseCapability(modelConfig.getCapabilityJson());
        if (Objects.isNull(capability)) {
            return;
        }
        String resolution = readFirstText(options, OPTION_SIZE_KEYS);
        validateOption(capability, KEY_SIZE_OPTIONS, resolution,
                modelConfig.getModelCode(), "清晰度不支持");
        String ratio = StrUtil.isNotBlank(aspectRatio) ? aspectRatio : readFirstText(options, OPTION_RATIO_KEYS);
        validateOption(capability, KEY_ASPECT_RATIO_OPTIONS, ratio,
                modelConfig.getModelCode(), "画面比例不支持");
        validateDuration(capability, durationSeconds, modelConfig.getModelCode());
    }

    /**
     * 单项白名单校验：白名单缺失/为空不拦；入参为空不拦；归一化后未命中抛短文案。
     *
     * @param capability   已解析的 capability 根节点
     * @param whitelistKey 白名单键名
     * @param value        待校验值（可空）
     * @param modelCode    模型编码（日志用）
     * @param errorMessage 未命中时的用户短文案
     */
    private static void validateOption(JsonNode capability, String whitelistKey, String value,
                                       String modelCode, String errorMessage) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        List<String> whitelist = readStringList(capability, whitelistKey);
        if (CollectionUtil.isEmpty(whitelist)) {
            return;
        }
        String normalized = normalize(value);
        for (String allowed : whitelist) {
            if (normalize(allowed).equals(normalized)) {
                return;
            }
        }
        log.info("模型能力校验未命中: modelCode={}, key={}, value={}, whitelist={}",
                modelCode, whitelistKey, value, whitelist);
        throw new ServiceException(errorMessage);
    }

    /**
     * 时长白名单校验：durationOptions 缺失/为空不拦；入参为空不拦；未命中抛「时长不支持」。
     *
     * @param capability      已解析的 capability 根节点
     * @param durationSeconds 请求时长秒
     * @param modelCode       模型编码（日志用）
     */
    private static void validateDuration(JsonNode capability, Integer durationSeconds, String modelCode) {
        if (Objects.isNull(durationSeconds)) {
            return;
        }
        JsonNode node = capability.get(KEY_DURATION_OPTIONS);
        if (Objects.isNull(node) || !node.isArray() || node.isEmpty()) {
            return;
        }
        for (JsonNode item : node) {
            if (item.isNumber() && item.intValue() == durationSeconds) {
                return;
            }
        }
        log.info("模型时长校验未命中: modelCode={}, duration={}, whitelist={}",
                modelCode, durationSeconds, node);
        throw new ServiceException("时长不支持");
    }

    /**
     * 解析 capability_json 为 JSON 树；空/非法返回 null（视为未声明能力，不拦截）。
     *
     * @param capabilityJson 能力 JSON 文本
     * @return 根节点或 null
     */
    private static JsonNode parseCapability(String capabilityJson) {
        if (StrUtil.isBlank(capabilityJson)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(capabilityJson);
            return root != null && root.isObject() ? root : null;
        } catch (Exception ex) {
            log.warn("capability_json 解析失败,跳过能力校验, err={}", ex.getMessage());
            return null;
        }
    }

    /**
     * 读取 capability 中的字符串数组白名单。
     *
     * @param capability 根节点
     * @param key        键名
     * @return 白名单（缺失/非数组返回空列表）
     */
    private static List<String> readStringList(JsonNode capability, String key) {
        List<String> result = new ArrayList<>();
        JsonNode node = capability.get(key);
        if (Objects.isNull(node) || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            if (item.isTextual() && StrUtil.isNotBlank(item.asText())) {
                result.add(item.asText());
            } else if (item.isNumber()) {
                result.add(item.asText());
            }
        }
        return result;
    }

    /**
     * 取 options 中首个非空文本值。
     *
     * @param options 扩展参数（可空）
     * @param keys    候选键（按优先级）
     * @return 首个非空值；均无返回 null
     */
    private static String readFirstText(Map<String, Object> options, String[] keys) {
        if (Objects.isNull(options) || options.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = options.get(key);
            if (value instanceof String && StrUtil.isNotBlank((String) value)) {
                return (String) value;
            }
        }
        return null;
    }

    /**
     * 归一化比对值：去空白、统一小写、尺寸分隔符 {@code *}/{@code ×} 统一为 {@code x}、
     * 全角冒号统一为半角（比例）。保证 "1K"/"1k"、"1024*1024"/"1024x1024"、"16：9"/"16:9" 视为等价。
     *
     * @param value 原值
     * @return 归一化结果
     */
    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT)
                .replace('*', 'x').replace('×', 'x')
                .replace('：', ':')
                .replaceAll("\\s+", "");
    }
}
