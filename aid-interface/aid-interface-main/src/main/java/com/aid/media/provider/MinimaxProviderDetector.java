package com.aid.media.provider;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * MiniMax 音频服务商归属判定器（业务层门禁与调度层路由共用同一份判定）
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class MinimaxProviderDetector {

    /** MiniMax 服务商 provider_code */
    public static final String PROVIDER_CODE_MINIMAX = "minimax";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 音频 provider client 列表：第三级兜底复用 supportsModel，与调度层路由物理一致 */
    @Autowired(required = false)
    private List<AudioProviderClient> audioProviderClients;

    /**
     * 三级判定模型是否归属 MiniMax：
     * providerCode → capability_json.provider → AudioProviderClient.supportsModel 兜底。
     *
     * @param providerCode       模型归属 provider_code（可空）
     * @param capabilityProvider capability_json 中声明的 provider（可空）
     * @param modelCode          模型编码（可空）
     * @return true=MiniMax 模型
     */
    public boolean isMinimax(String providerCode, String capabilityProvider, String modelCode) {
        // 第一优先级：providerCode
        if (StrUtil.isNotBlank(providerCode)
                && PROVIDER_CODE_MINIMAX.equalsIgnoreCase(providerCode.trim())) {
            return true;
        }
        // 第二优先级：capability_json.provider
        if (StrUtil.isNotBlank(capabilityProvider)
                && PROVIDER_CODE_MINIMAX.equalsIgnoreCase(capabilityProvider.trim())) {
            return true;
        }
        // 第三优先级：复用 MinimaxTtsProviderClient.supportsModel 本身，和调度层第三级物理一致
        if (StrUtil.isNotBlank(modelCode) && CollectionUtil.isNotEmpty(audioProviderClients)) {
            for (AudioProviderClient client : audioProviderClients) {
                if (client.supportsProviderCode(PROVIDER_CODE_MINIMAX) && client.supportsModel(modelCode)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从 capability_json 解析 provider 字段
     *
     * @param capabilityJson 模型能力配置JSON
     * @return provider 标识；缺失/解析失败返回 null（调用方视为无声明）
     */
    public static String parseCapabilityProvider(String capabilityJson) {
        if (StrUtil.isBlank(capabilityJson)) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(capabilityJson);
            JsonNode node = root.get("provider");
            if (Objects.nonNull(node) && !node.isNull() && StrUtil.isNotBlank(node.asText())) {
                return node.asText().trim();
            }
        } catch (Exception e) {
            log.warn("parseCapabilityProvider 解析失败: capabilityJson={}, err={}",
                    StrUtil.brief(capabilityJson, 80), e.getMessage());
        }
        return null;
    }
}
