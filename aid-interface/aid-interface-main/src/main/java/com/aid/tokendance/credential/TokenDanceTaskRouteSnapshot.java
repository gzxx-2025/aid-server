package com.aid.tokendance.credential;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 固化 TokenDance 内部任务路由；主密钥仅在执行时按版本读取。 */
public final class TokenDanceTaskRouteSnapshot {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TokenDanceTaskRouteSnapshot() { }

    public static String capture(AiModelConfigVo config) {
        if (config == null || !"tokendance".equalsIgnoreCase(config.getProviderCode())) return null;
        if (config.getProviderId() == null || config.getCredentialVersion() == null) {
            throw new ServiceException("凭证版本无效");
        }
        ObjectNode snapshot = MAPPER.valueToTree(config);
        if (config.getResolvedDefinition() != null) snapshot.set("taskCapabilityDefinition", MAPPER.valueToTree(config.getResolvedDefinition()));
        ObjectNode extensions = MAPPER.createObjectNode();
        for (String field : java.util.List.of("extraHeadersJson", "extraBodyJson", "extraQueryJson", "modelExtraBodyJson")) {
            if (snapshot.hasNonNull(field)) extensions.set(field, snapshot.get(field));
        }
        if (!extensions.isEmpty()) {
            if (extensions.toString().length() > 100000) throw new ServiceException("模型扩展配置过大");
            snapshot.set("routeExtensions", extensions);
        }
        // 内部路由快照不得返回客户端；主 API Key 始终按独立版本引用，不复制到任务。
        snapshot.remove(java.util.List.of("apiKey", "apiSecret", "extraHeadersJson", "extraBodyJson",
                "extraQueryJson", "modelExtraBodyJson"));
        return snapshot.toString();
    }

    public static AiModelConfigVo restore(String json, TokenDanceCredentialStore store) {
        try {
            ObjectNode snapshot = (ObjectNode) MAPPER.readTree(json);
            var extensions = snapshot.remove("routeExtensions");
            var definition = snapshot.remove("taskCapabilityDefinition");
            AiModelConfigVo config = MAPPER.treeToValue(snapshot, AiModelConfigVo.class);
            if (definition != null && !definition.isNull()) config.setResolvedDefinition(MAPPER.treeToValue(definition,
                    com.aid.aid.domain.model.ModelCapabilityDefinition.class));
            if (!"tokendance".equalsIgnoreCase(config.getProviderCode()) || config.getProviderId() == null
                    || config.getCredentialVersion() == null) throw new ServiceException("任务路由快照无效");
            if (extensions != null && !extensions.isNull()) {
                config.setExtraHeadersJson(extensions.path("extraHeadersJson").asText(null));
                config.setExtraBodyJson(extensions.path("extraBodyJson").asText(null));
                config.setExtraQueryJson(extensions.path("extraQueryJson").asText(null));
                config.setModelExtraBodyJson(extensions.path("modelExtraBodyJson").asText(null));
            }
            ResolvedTokenDanceCredential credential = store.requireVersion(
                    config.getProviderId(), config.getCredentialVersion());
            config.setApiKey(credential.getApiKey());
            config.setApiSecret(null);
            return config;
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServiceException("任务路由快照无效");
        }
    }

}
