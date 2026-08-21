package com.aid.media.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/** 模型能力参数解析器。 */
@Slf4j
public final class ModelCapabilityResolver {

    /** capability_json 键：清晰度档位白名单 */
    public static final String KEY_SIZE_OPTIONS = "sizeOptions";

    /** capability_json 键：默认清晰度档位 */
    public static final String KEY_DEFAULT_SIZE = "defaultSize";

    /** capability_json 键：画面比例白名单 */
    public static final String KEY_ASPECT_RATIO_OPTIONS = "aspectRatioOptions";

    /** capability_json 键：默认画面比例 */
    public static final String KEY_DEFAULT_ASPECT_RATIO = "defaultAspectRatio";

    /** capability_json 键：视频比例控制方式（PARAMETER / FOLLOW_INPUT） */
    public static final String KEY_VIDEO_ASPECT_RATIO_MODE = "videoAspectRatioMode";

    /** 视频输出比例由输入图决定。 */
    public static final String VIDEO_ASPECT_RATIO_MODE_FOLLOW_INPUT = "FOLLOW_INPUT";

    /** 可用于输入图归一化的具体宽高比。 */
    private static final Pattern CONCRETE_ASPECT_RATIO =
            Pattern.compile("(?:0|[1-9]\\d*)(?:\\.\\d+)?:(?:0|[1-9]\\d*)(?:\\.\\d+)?");

    /** capability_json 键：输入图适配目标比例的策略（CONTAIN / COVER）。 */
    public static final String KEY_INPUT_ASPECT_RATIO_FIT = "inputAspectRatioFit";

    /** 清晰度未命中白名单的用户短文案 */
    public static final String MSG_SIZE_UNSUPPORTED = "清晰度不支持";

    /** 画面比例未命中白名单的用户短文案 */
    public static final String MSG_ASPECT_RATIO_UNSUPPORTED = "画面比例不支持";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelCapabilityResolver() {
    }

    /**
     * 解析最终下发的清晰度档位。
     *
     * @param modelConfig 模型聚合配置
     * @param requested   调用方期望档位（可空）
     * @return 白名单内的规范写法；模型未声明档位且无默认值时返回 null（不下发，走 Provider 自身默认）
     */
    public static String resolveSize(AiModelConfigVo modelConfig, String requested) {
        if (Objects.isNull(modelConfig)) {
            return StrUtil.trimToNull(requested);
        }
        JsonNode capability = parseCapability(modelConfig.getCapabilityJson());
        String fallback = StrUtil.blankToDefault(
                readText(capability, KEY_DEFAULT_SIZE), modelConfig.getDefaultSizeCode());
        return resolve(capability, modelConfig.getModelCode(), requested,
                KEY_SIZE_OPTIONS, fallback, MSG_SIZE_UNSUPPORTED);
    }

    /**
     * 解析最终下发的画面比例。
     *
     * @param modelConfig 模型聚合配置
     * @param requested   调用方期望比例（可空）
     * @return 白名单内的规范写法；模型未声明比例且无默认值时返回 null（不下发）
     */
    public static String resolveAspectRatio(AiModelConfigVo modelConfig, String requested) {
        if (Objects.isNull(modelConfig)) {
            return StrUtil.trimToNull(requested);
        }
        JsonNode capability = parseCapability(modelConfig.getCapabilityJson());
        String fallback = StrUtil.blankToDefault(
                readText(capability, KEY_DEFAULT_ASPECT_RATIO), modelConfig.getDefaultAspectRatio());
        return resolve(capability, modelConfig.getModelCode(), requested,
                KEY_ASPECT_RATIO_OPTIONS, fallback, MSG_ASPECT_RATIO_UNSUPPORTED);
    }

    /** 解析视频画面比例。 */
    public static String resolveVideoAspectRatio(AiModelConfigVo modelConfig, String requested) {
        if (!isVideoAspectRatioFollowInput(modelConfig)) {
            return resolveAspectRatio(modelConfig, requested);
        }
        String requestedValue = StrUtil.trimToNull(requested);
        if (StrUtil.isBlank(requestedValue)) {
            return resolveVideoProviderAspectRatio(modelConfig);
        }
        JsonNode capability = parseCapability(modelConfig.getCapabilityJson());
        String configuredValue = matchOption(
                readOptions(capability, KEY_ASPECT_RATIO_OPTIONS), requestedValue);
        if (Objects.nonNull(configuredValue)) {
            return configuredValue;
        }
        String providerValue = resolveVideoProviderAspectRatio(modelConfig);
        if (StrUtil.isNotBlank(providerValue)
                && normalize(providerValue).equals(normalize(requestedValue))) {
            return providerValue;
        }
        String normalized = normalize(requestedValue);
        if (isConcreteAspectRatio(normalized)) {
            return normalized;
        }
        log.info("跟随输入视频目标比例格式错误: modelCode={}, aspectRatio={}",
                modelConfig.getModelCode(), requestedValue);
        throw new ServiceException(MSG_ASPECT_RATIO_UNSUPPORTED);
    }

    /** 读取 FOLLOW_INPUT 模型提交给 Provider 的比例值。 */
    public static String resolveVideoProviderAspectRatio(AiModelConfigVo modelConfig) {
        return resolveAspectRatio(modelConfig, null);
    }

    /** 判断比例是否为可计算的正数 W:H。 */
    public static boolean isConcreteAspectRatio(String value) {
        String normalized = StrUtil.isBlank(value) ? null : normalize(value);
        if (StrUtil.isBlank(normalized) || !CONCRETE_ASPECT_RATIO.matcher(normalized).matches()) {
            return false;
        }
        String[] dimensions = normalized.split(":", -1);
        try {
            double width = Double.parseDouble(dimensions[0]);
            double height = Double.parseDouble(dimensions[1]);
            return width > 0D && height > 0D
                    && Double.isFinite(width) && Double.isFinite(height);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /**
     * 判断视频模型是否通过输入图片比例控制输出，而不是向厂商提交独立比例参数。
     * 新配置优先读取顶层 videoAspectRatioMode；兼容历史配置时，仅在模型未声明比例参数能力的情况下
     * 扫描 sceneRules.aspectRatioFollowInput。
     */
    public static boolean isVideoAspectRatioFollowInput(AiModelConfigVo modelConfig) {
        if (Objects.isNull(modelConfig)) {
            return false;
        }
        JsonNode capability = parseCapability(modelConfig.getCapabilityJson());
        if (Objects.isNull(capability)) {
            return false;
        }
        String mode = readText(capability, KEY_VIDEO_ASPECT_RATIO_MODE);
        if (StrUtil.isNotBlank(mode)) {
            return VIDEO_ASPECT_RATIO_MODE_FOLLOW_INPUT.equalsIgnoreCase(mode);
        }
        if (Boolean.TRUE.equals(modelConfig.getSupportsAspectRatio())) {
            return false;
        }
        JsonNode sceneRules = capability.path("sceneRules");
        if (!sceneRules.isObject()) {
            return false;
        }
        for (JsonNode rule : sceneRules) {
            if (rule.path("aspectRatioFollowInput").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取图片场景白名单：场景级配置优先，未配置时回退模型顶层白名单。
     */
    public static List<String> readImageSceneOptions(AiModelConfigVo modelConfig,
                                                     String sceneCode, String optionsKey) {
        if (Objects.isNull(modelConfig)) {
            return List.of();
        }
        JsonNode capability = parseCapability(modelConfig.getCapabilityJson());
        if (Objects.isNull(capability)) {
            return List.of();
        }
        JsonNode sceneCapability = StrUtil.isBlank(sceneCode)
                ? null : capability.path("sceneRules").path(sceneCode);
        List<String> sceneOptions = readOptions(sceneCapability, optionsKey);
        return CollectionUtil.isNotEmpty(sceneOptions) ? sceneOptions : readOptions(capability, optionsKey);
    }

    /**
     * 修复存量图片配置中的清晰度：有效值保持原样，无效值回退场景默认、模型默认或白名单首项。
     */
    public static String coerceImageSceneSize(AiModelConfigVo modelConfig,
                                              String sceneCode, String current) {
        return coerceImageSceneOption(modelConfig, sceneCode, current,
                KEY_SIZE_OPTIONS, KEY_DEFAULT_SIZE,
                Objects.isNull(modelConfig) ? null : modelConfig.getDefaultSizeCode());
    }

    /**
     * 修复存量图片配置中的比例：有效值保持原样，无效值回退场景默认、模型默认或白名单首项。
     */
    public static String coerceImageSceneAspectRatio(AiModelConfigVo modelConfig,
                                                     String sceneCode, String current) {
        return coerceImageSceneOption(modelConfig, sceneCode, current,
                KEY_ASPECT_RATIO_OPTIONS, KEY_DEFAULT_ASPECT_RATIO,
                Objects.isNull(modelConfig) ? null : modelConfig.getDefaultAspectRatio());
    }

    /** 场景配置修复公共实现，仅用于读取历史配置，不改变直接生成接口的严格校验语义。 */
    private static String coerceImageSceneOption(AiModelConfigVo modelConfig, String sceneCode,
                                                 String current, String optionsKey,
                                                 String defaultKey, String modelDefault) {
        if (Objects.isNull(modelConfig)) {
            return StrUtil.trimToNull(current);
        }
        JsonNode capability = parseCapability(modelConfig.getCapabilityJson());
        if (Objects.isNull(capability)) {
            return StrUtil.trimToNull(current);
        }
        JsonNode sceneCapability = StrUtil.isBlank(sceneCode)
                ? null : capability.path("sceneRules").path(sceneCode);
        List<String> options = readOptions(sceneCapability, optionsKey);
        if (CollectionUtil.isEmpty(options)) {
            options = readOptions(capability, optionsKey);
        }
        String matched = matchOption(options, current);
        if (Objects.nonNull(matched)) {
            return matched;
        }
        String fallback = StrUtil.blankToDefault(readText(sceneCapability, defaultKey),
                StrUtil.blankToDefault(readText(capability, defaultKey), modelDefault));
        if (CollectionUtil.isEmpty(options)) {
            return StrUtil.blankToDefault(current, fallback);
        }
        String matchedFallback = matchOption(options, fallback);
        String repaired = Objects.nonNull(matchedFallback) ? matchedFallback : options.get(0);
        if (StrUtil.isNotBlank(current)) {
            log.warn("图片场景存量配置不在能力白名单内已回退: modelCode={}, scene={}, key={}, value={}, repaired={}",
                    modelConfig.getModelCode(), sceneCode, optionsKey, current, repaired);
        }
        return repaired;
    }

    /**
     * 联合解析图片的清晰度档位与画面比例，并消解两者的互斥。
     *
     * <p>显式像素尺寸（如 {@code 1024x1024}）本身已经蕴含比例，各厂商协议一律「显式像素优先、比例被丢弃」
     * （见 Agnes / 即梦 / 万相的尺寸翻译分支）。因此当调用方只表达了比例意图、而档位是由模型默认档兜底
     * 得到的显式像素时，补这个档位会把调用方选的比例挤掉，必须不下发；反之调用方两者都没给时，
     * 保留显式像素档位并丢弃兜底比例，避免下发一对自相矛盾的参数。</p>
     *
     * @param modelConfig          模型聚合配置
     * @param requestedSize        调用方期望档位（可空）
     * @param requestedAspectRatio 调用方期望比例（可空）
     * @return 可直接下发的档位 + 比例组合
     */
    public static ImageSizeSpec resolveImageSpec(AiModelConfigVo modelConfig,
                                                 String requestedSize, String requestedAspectRatio) {
        String size = resolveSize(modelConfig, requestedSize);
        String aspectRatio = resolveAspectRatio(modelConfig, requestedAspectRatio);
        if (StrUtil.isNotBlank(requestedSize) || !isExplicitPixelSize(size)) {
            return new ImageSizeSpec(size, aspectRatio);
        }
        String modelCode = Objects.isNull(modelConfig) ? null : modelConfig.getModelCode();
        if (StrUtil.isNotBlank(requestedAspectRatio)) {
            log.info("模型默认档位为显式像素与比例互斥，按调用方比例出图不下发默认档: modelCode={}, defaultSize={}, aspectRatio={}",
                    modelCode, size, aspectRatio);
            return new ImageSizeSpec(null, aspectRatio);
        }
        if (StrUtil.isNotBlank(aspectRatio)) {
            log.info("模型默认档位为显式像素已蕴含比例，丢弃兜底比例: modelCode={}, defaultSize={}, defaultAspectRatio={}",
                    modelCode, size, aspectRatio);
        }
        return new ImageSizeSpec(size, null);
    }

    /** 是否显式像素尺寸（含宽高分隔符），与档位串（1K / 2K / 1080p）区分。 */
    private static boolean isExplicitPixelSize(String size) {
        return StrUtil.isNotBlank(size) && normalize(size).indexOf('x') >= 0;
    }

    /**
     * 图片档位与比例的联合解析结果。
     *
     * @param size        可下发的清晰度档位；不下发时为 null
     * @param aspectRatio 可下发的画面比例；不下发时为 null
     */
    public record ImageSizeSpec(String size, String aspectRatio) {
    }

    /**
     * 白名单单项解析：传值优先（未命中抛短文案），未传值按默认档 → 白名单首项兜底。
     *
     * @param capability   已解析的 capability 根节点（可空）
     * @param modelCode    模型编码（日志用）
     * @param requested    调用方传值（可空）
     * @param optionsKey   白名单键名
     * @param defaultValue 模型默认值（可空）
     * @param errorMessage 未命中时的用户短文案
     * @return 规范写法；无可用值返回 null
     */
    private static String resolve(JsonNode capability, String modelCode, String requested,
                                  String optionsKey, String defaultValue, String errorMessage) {
        List<String> whitelist = readOptions(capability, optionsKey);
        if (StrUtil.isNotBlank(requested)) {
            String trimmed = requested.trim();
            // 白名单缺失 = 模型未声明该能力，不拦截调用方传值
            if (CollectionUtil.isEmpty(whitelist)) {
                return trimmed;
            }
            String matched = matchOption(whitelist, trimmed);
            if (Objects.nonNull(matched)) {
                return matched;
            }
            log.info("模型能力解析未命中: modelCode={}, key={}, value={}, whitelist={}",
                    modelCode, optionsKey, trimmed, whitelist);
            throw new ServiceException(errorMessage);
        }
        if (StrUtil.isNotBlank(defaultValue)) {
            if (CollectionUtil.isEmpty(whitelist)) {
                return defaultValue.trim();
            }
            String matched = matchOption(whitelist, defaultValue);
            if (Objects.nonNull(matched)) {
                return matched;
            }
            log.warn("模型默认值不在白名单内已回退白名单首项: modelCode={}, key={}, default={}, whitelist={}",
                    modelCode, optionsKey, defaultValue, whitelist);
        }
        return CollectionUtil.isEmpty(whitelist) ? null : whitelist.get(0);
    }

    /**
     * 在白名单中按归一化口径查找目标值。
     *
     * @param whitelist 白名单
     * @param value     待匹配值
     * @return 命中的白名单规范写法；未命中返回 null
     */
    public static String matchOption(List<String> whitelist, String value) {
        if (StrUtil.isBlank(value) || CollectionUtil.isEmpty(whitelist)) {
            return null;
        }
        String normalized = normalize(value);
        for (String option : whitelist) {
            if (StrUtil.isNotBlank(option) && normalize(option).equals(normalized)) {
                return option.trim();
            }
        }
        return null;
    }

    /**
     * 归一化比对值：去空白、统一小写、尺寸分隔符 {@code *}/{@code ×} 统一为 {@code x}、
     * 全角冒号统一为半角。保证 "1K"/"1k"、"1024*1024"/"1024x1024"、"16：9"/"16:9" 视为等价。
     *
     * @param value 原值
     * @return 归一化结果
     */
    public static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT)
                .replace('*', 'x').replace('×', 'x')
                .replace('：', ':')
                .replaceAll("\\s+", "");
    }

    /**
     * 解析 capability_json 为 JSON 树；空/非法返回 null（视为未声明能力）。
     *
     * @param capabilityJson 能力 JSON 文本
     * @return 根节点或 null
     */
    public static JsonNode parseCapability(String capabilityJson) {
        if (StrUtil.isBlank(capabilityJson)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(capabilityJson);
            return Objects.nonNull(root) && root.isObject() ? root : null;
        } catch (Exception ex) {
            log.warn("capability_json 解析失败按未声明能力处理, err={}", ex.getMessage());
            return null;
        }
    }

    /**
     * 读取能力中的字符串数组白名单。
     *
     * @param capability 根节点（可空）
     * @param key        键名
     * @return 白名单；缺失/非数组返回空列表
     */
    public static List<String> readOptions(JsonNode capability, String key) {
        List<String> result = new ArrayList<>();
        if (Objects.isNull(capability)) {
            return result;
        }
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
     * 读取能力中的文本字段。
     *
     * @param capability 根节点（可空）
     * @param key        键名
     * @return 文本值；缺失返回 null
     */
    public static String readText(JsonNode capability, String key) {
        if (Objects.isNull(capability)) {
            return null;
        }
        JsonNode node = capability.get(key);
        return Objects.nonNull(node) && node.isTextual() ? StrUtil.trimToNull(node.asText()) : null;
    }
}
