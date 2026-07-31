package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.domain.vo.AiModelConfigVo;
import lombok.extern.slf4j.Slf4j;

/**
 * 单次 provider HTTP 调用超时统一解析器（厂商无关）。
 */
@Slf4j
public final class SubmitTimeoutResolver {

    private SubmitTimeoutResolver() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** capability_json 中的单次提交超时键（单位：秒） */
    private static final String KEY_SUBMIT_TIMEOUT_SECONDS = "submitTimeoutSeconds";

    /** 配置允许的最小秒数 */
    private static final int MIN_SECONDS = 1;

    /** 配置允许的最大秒数 */
    private static final int MAX_SECONDS = 3600;

    /**
     * 解析单次 HTTP 超时（毫秒）。
     *
     * @param modelConfig 模型聚合配置（含 capabilityJson）
     * @param fallbackMs  厂商默认超时毫秒（capability 未配置 / 非法时回退，保持原行为）
     * @return 生效的超时毫秒数
     */
    public static int resolveMs(AiModelConfigVo modelConfig, int fallbackMs) {
        Integer seconds = readSeconds(modelConfig == null ? null : modelConfig.getCapabilityJson());
        if (seconds == null) {
            return fallbackMs;
        }
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            log.warn("capability_json.submitTimeoutSeconds={} 超出[{}~{}]范围，回退默认 {}ms",
                    seconds, MIN_SECONDS, MAX_SECONDS, fallbackMs);
            return fallbackMs;
        }
        return seconds * 1000;
    }

    /**
     * 从 capability_json 读取 {@code submitTimeoutSeconds}；缺失 / 解析失败 / 非数字返回 null。
     */
    private static Integer readSeconds(String capabilityJson) {
        if (StrUtil.isBlank(capabilityJson)) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(capabilityJson).get(KEY_SUBMIT_TIMEOUT_SECONDS);
            if (node != null && node.isNumber()) {
                return (int) Math.floor(node.doubleValue());
            }
        } catch (Exception e) {
            log.warn("解析 capability_json.submitTimeoutSeconds 失败, err={}", e.getMessage());
        }
        return null;
    }
}
