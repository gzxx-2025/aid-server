package com.aid.model.support;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.common.exception.ServiceException;
import com.aid.model.vo.CapabilityVO;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 图片/视频模型能力守卫：请求档位不在模型 capability 枚举内时提前拦截，避免脏参数打到上游报错扣时。
 *
 * @author 视觉AID
 */
@Slf4j
public final class ModelCapabilityGuard {

    private ModelCapabilityGuard() {
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 解析 capability_json；空或解析失败返回 null（宽松放行，保持旧模型兼容）。
     *
     * @param capabilityJson 模型 capability_json 原文
     * @return 解析结果；无法解析时为 null
     */
    public static CapabilityVO parseOrNull(String capabilityJson) {
        if (StrUtil.isBlank(capabilityJson)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(capabilityJson, CapabilityVO.class);
        } catch (Exception e) {
            log.warn("capability_json 解析失败, 能力校验降级放行: err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 校验清晰度档位：模型声明了 sizeOptions 且请求带 size 时，size 必须命中枚举（忽略大小写）。
     * 模型未声明 sizeOptions 或请求未带 size 时不拦截。
     *
     * @param capability 已解析能力（可为 null）
     * @param size       请求清晰度档位（如 1K/2K/4K/1024x1024）
     * @param modelCode  模型编码（日志用）
     */
    public static void assertSizeSupported(CapabilityVO capability, String size, String modelCode) {
        if (Objects.isNull(capability) || StrUtil.isBlank(size)) {
            return;
        }
        List<String> sizeOptions = capability.getSizeOptions();
        if (CollectionUtil.isEmpty(sizeOptions)) {
            return;
        }
        String requested = size.trim();
        boolean matched = sizeOptions.stream()
                .filter(StrUtil::isNotBlank)
                .anyMatch(opt -> opt.trim().equalsIgnoreCase(requested));
        if (!matched) {
            log.info("模型清晰度档位不支持: modelCode={}, size={}, supported={}", modelCode, requested, sizeOptions);
            throw new ServiceException("清晰度不符");
        }
    }

    /**
     * 校验宽高比：模型声明了 aspectRatioOptions 且请求带比例时，必须命中枚举（忽略大小写）。
     * 模型未声明比例枚举或请求未带比例时不拦截。
     *
     * @param capability  已解析能力（可为 null）
     * @param aspectRatio 请求宽高比（如 16:9）
     * @param modelCode   模型编码（日志用）
     */
    public static void assertAspectRatioSupported(CapabilityVO capability, String aspectRatio, String modelCode) {
        if (Objects.isNull(capability) || StrUtil.isBlank(aspectRatio)) {
            return;
        }
        List<String> ratioOptions = capability.getAspectRatioOptions();
        if (CollectionUtil.isEmpty(ratioOptions)) {
            return;
        }
        String requested = aspectRatio.trim();
        boolean matched = ratioOptions.stream()
                .filter(StrUtil::isNotBlank)
                .anyMatch(opt -> opt.trim().equalsIgnoreCase(requested));
        if (!matched) {
            log.info("模型比例不支持: modelCode={}, aspectRatio={}, supported={}", modelCode, requested, ratioOptions);
            throw new ServiceException("比例不符");
        }
    }
}
