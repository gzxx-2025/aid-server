package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.domain.vo.AiModelConfigVo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 参考图「数量上限」统一限制器。
 */
@Slf4j
public final class ReferenceImageLimiter {

    private ReferenceImageLimiter() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** capability_json 中的参考图数量上限键 */
    private static final String KEY_MAX_REFERENCE_IMAGES = "maxReferenceImages";

    /** capability_json 中的参考图数量下限键（必须带图的模型配 N>=1，纯文生模型不配或配 0） */
    private static final String KEY_MIN_REFERENCE_IMAGES = "minReferenceImages";

    /** 无限标识（解析/计算后用 Integer.MAX_VALUE 表达"不限张数"） */
    private static final int UNLIMITED = Integer.MAX_VALUE;

    /** 禁止参考图标识（capability 配 0） */
    public static final int FORBID = 0;

    /**
     * 解析模型配置的参考图上限（四态语义，见类注释）。
     *
     * @param modelConfig 模型聚合配置（含 capabilityJson）
     * @param fallbackMax 厂商默认上限（仅在 capability 未配置时使用；&lt;=0 视为无限）
     * @return 生效的最大张数：{@link #UNLIMITED}=无限，0=禁止，正数=上限
     */
    public static int resolveMax(AiModelConfigVo modelConfig, int fallbackMax) {
        Integer configured = readMaxFromCapability(modelConfig);
        if (configured == null) {
            // 未配置 → 厂商默认兜底（fallback<=0 视为无限）
            return fallbackMax > 0 ? fallbackMax : UNLIMITED;
        }
        if (configured < 0) {
            // -1 等负数 → 无限
            return UNLIMITED;
        }
        // 0 → 禁止；N>0 → 上限 N
        return configured;
    }

    /**
     * 从 capability_json 读取 {@code maxReferenceImages}；缺失 / 解析失败 / 非数字返回 null。
     */
    public static Integer readMaxFromCapability(AiModelConfigVo modelConfig) {
        return readMaxFromCapabilityJson(modelConfig == null ? null : modelConfig.getCapabilityJson());
    }

    /**
     * 从 capability_json 文本读取 {@code maxReferenceImages}；缺失 / 解析失败 / 非数字返回 null。
     * 供 C 端模型列表组装 VO 等无 {@link AiModelConfigVo} 的场景直接传 JSON 字符串使用。
     */
    public static Integer readMaxFromCapabilityJson(String capabilityJson) {
        if (StrUtil.isBlank(capabilityJson)) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(capabilityJson).get(KEY_MAX_REFERENCE_IMAGES);
            if (node != null && node.isInt()) {
                return node.intValue();
            }
            if (node != null && node.isNumber()) {
                return (int) Math.floor(node.doubleValue());
            }
        } catch (Exception e) {
            log.warn("解析 capability_json.maxReferenceImages 失败, err={}", e.getMessage());
        }
        return null;
    }

    /**
     * 从 capability_json 文本读取 {@code minReferenceImages}（该模型至少需要几张输入图）。
     * 缺失 / 解析失败 / 非数字 / 负数一律返回 0（不要求带图）。
     */
    public static int readMinFromCapabilityJson(String capabilityJson) {
        if (StrUtil.isBlank(capabilityJson)) {
            return 0;
        }
        try {
            JsonNode node = MAPPER.readTree(capabilityJson).get(KEY_MIN_REFERENCE_IMAGES);
            if (node != null && node.isNumber()) {
                int value = (int) Math.floor(node.doubleValue());
                return Math.max(value, 0);
            }
        } catch (Exception e) {
            log.warn("解析 capability_json.minReferenceImages 失败, err={}", e.getMessage());
        }
        return 0;
    }

    /**
     * C 端展示用：把 capability 配置换算成前端可直接用的"最多参考图张数"。
     *
     * @param modelType      模型类型（text/image/video/audio）
     * @param capabilityJson 模型 capability_json 文本
     * @return 前端展示用张数（999 表示无限）
     */
    public static int resolveDisplayMax(String modelType, String capabilityJson) {
        if (!"image".equals(modelType) && !"video".equals(modelType)) {
            return 0;
        }
        Integer configured = readMaxFromCapabilityJson(capabilityJson);
        if (configured == null || configured < 0) {
            return 999;
        }
        return configured;
    }

    /**
     * 按模型配置的上限截断参考图列表（统一入口）：超限按顺序保留前 N 张并打 warn，不抛错。
     *
     * @param images      已合并的有序参考图列表（URL 或 Data URI）
     * @param modelConfig 模型配置（读 capability_json.maxReferenceImages）
     * @param fallbackMax 配置缺失时的厂商默认上限
     * @param providerTag 日志用厂商标识（如 "Agnes"/"Vidu"/"即梦4.0"）
     * @return 截断后的列表（不超过生效上限）；入参为空返回空列表
     */
    public static List<String> limit(List<String> images, AiModelConfigVo modelConfig,
                                     int fallbackMax, String providerTag) {
        if (images == null || images.isEmpty()) {
            return images == null ? new ArrayList<>() : images;
        }
        int max = resolveMax(modelConfig, fallbackMax);
        // 0 → 禁止参考图：丢弃全部，强制纯文生图
        if (max == FORBID) {
            log.warn("{} 模型已禁用参考图(maxReferenceImages=0)，丢弃{}张参考图转纯文生图", providerTag, images.size());
            return new ArrayList<>();
        }
        if (images.size() <= max) {
            return images;
        }
        log.warn("{} 参考图超过上限按顺序截断: max={}, 实际={}, 仅保留前{}张", providerTag, max, images.size(), max);
        return new ArrayList<>(images.subList(0, max));
    }
}
