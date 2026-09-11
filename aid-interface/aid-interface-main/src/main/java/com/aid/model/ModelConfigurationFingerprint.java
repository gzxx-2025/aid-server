package com.aid.model;

import cn.hutool.crypto.SecureUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 报价与提交之间的模型配置一致性指纹，不包含凭证明文。 */
public final class ModelConfigurationFingerprint {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ModelConfigurationFingerprint() { }
    public static String of(AiModelConfigVo config) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("model", config.getModelCode());
        values.put("realModel", config.getRealModelCode());
        values.put("provider", config.getProviderId());
        values.put("credentialVersion", config.getCredentialVersion());
        values.put("protocol", config.getProtocol());
        values.put("baseUrl", config.getBaseUrl());
        values.put("extraHeaders", config.getExtraHeadersJson());
        values.put("extraQuery", config.getExtraQueryJson());
        values.put("extraBody", config.getExtraBodyJson());
        values.put("modelExtraBody", config.getModelExtraBodyJson());
        values.put("capability", config.getCapabilityJson());
        values.put("billing", config.getBillingRuleJson());
        values.put("billingMode", config.getBillingMode());
        values.put("costCredits", config.getCostCredits());
        values.put("isFree", config.getIsFree());
        values.put("multiplier", config.getBillingMultiplier());
        try { return SecureUtil.sha256(MAPPER.writeValueAsString(values)); }
        catch (Exception ex) { throw new IllegalStateException("模型配置无法序列化"); }
    }
}
