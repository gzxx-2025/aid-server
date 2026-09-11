package com.aid.model.definition;

import cn.hutool.core.bean.BeanUtil;
import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelProtocolBinding;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/** 保存任务提交时的模型能力和路由，主调用密钥在执行时解析。 */
@Slf4j
public final class ModelTaskRouteSnapshot {
    private static final Set<String> FIELDS = Set.of("id", "providerId", "modelCode", "modelName", "modelType", "realModelCode",
            "capabilityCode", "bindingCode", "configVersion", "generateMode", "protocol", "apiSuffix", "apiVersion", "taskQuerySuffix",
            "capabilityJson", "paramMappingJson", "modelExtraBodyJson", "billingMode", "billingRuleJson", "billingVersion",
            "billingMultiplier", "costCredits", "isFree", "baseUrl", "providerCode", "authHeader", "authPrefix", "extraHeadersJson", "extraQueryJson", "extraBodyJson", "requestMappings");

    private ModelTaskRouteSnapshot() { }

    public static String capture(AiModelConfigVo config) {
        if (config == null) return null;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshotKind", "model-capability");
        snapshot.put("resolvedDefinition", config.getResolvedDefinition());
        snapshot.put("businessDefaultsJson", config.getBusinessDefaultsJson());
        snapshot.put("businessFuncCode", config.getBusinessFuncCode());
        for (String field : FIELDS) snapshot.put(field, BeanUtil.getProperty(config, field));
        for (String field : ModelInvocationResolver.PRESENTATION_FIELDS) snapshot.put(field, BeanUtil.getProperty(config, field));
        String json = JSON.toJSONString(snapshot, JSONWriter.Feature.WriteMapNullValue);
        if (json.length() > 100000) { log.info("任务模型配置超过限制: modelId={}", config.getId()); throw new ServiceException("模型配置过大"); }
        return json;
    }

    public static boolean supports(String json) {
        if (json == null || json.isBlank()) return false;
        JSONObject snapshot = JSON.parseObject(json);
        return snapshot != null && "model-capability".equals(snapshot.getString("snapshotKind"));
    }

    public static AiModelConfigVo restore(String json, AiModelConfigVo credentials) {
        if (credentials == null) throw new ServiceException("任务模型不可用");
        JSONObject snapshot = JSON.parseObject(json);
        // 当前凭证不能被自动发送到已经改掉的网关；需要恢复原凭证路由后重试查询。
        if (snapshot.containsKey("baseUrl") && !Objects.equals(snapshot.getString("baseUrl"), credentials.getBaseUrl())) {
            log.info("任务原供应商网关已变更，当前凭证不能用于历史网关: modelId={}", credentials.getId());
            throw new ServiceException("请恢复任务原供应商配置");
        }
        if (!Objects.equals(snapshot.getLong("providerId"), credentials.getProviderId())) {
            log.info("任务路由供应商不一致: modelId={}", credentials.getId());
            throw new ServiceException("任务路由不一致");
        }
        for (String field : FIELDS) if (!"requestMappings".equals(field) && snapshot.containsKey(field)) BeanUtil.setProperty(credentials, field, snapshot.get(field));
        if (snapshot.getJSONArray("requestMappings") != null) credentials.setRequestMappings(snapshot.getJSONArray("requestMappings")
                .toJavaList(ModelProtocolBinding.FieldMapping.class));
        credentials.setResolvedDefinition(snapshot.getObject("resolvedDefinition", ModelCapabilityDefinition.class));
        credentials.setBusinessDefaultsJson(snapshot.getString("businessDefaultsJson"));
        credentials.setBusinessFuncCode(snapshot.getString("businessFuncCode"));
        ModelInvocationResolver.applyPresentation(credentials, snapshot);
        return credentials;
    }
}
