package com.aid.tokendance.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;

/** 独立于可更新目录的 AID 兼容性禁止导入策略。 */
final class TokenDanceCompatibilityPolicy {
    private static final JsonNode LOCAL = load();

    private TokenDanceCompatibilityPolicy() { }

    static String blockedReason(JsonNode bundle, String modelId) {
        // 内置策略优先；远程目录删除字段、改为 false 或换用其他协议都不能解除本地禁入。
        JsonNode local = LOCAL.path("models").path(modelId);
        if (local.path("importBlocked").asBoolean(false)) {
            return local.path("reason").asText("不兼容 AID，禁止导入");
        }
        JsonNode remote = bundle.path("aidCompatibilityPolicy").path("models").path(modelId);
        return remote.path("importBlocked").asBoolean(false)
                ? remote.path("reason").asText("不兼容 AID，禁止导入") : null;
    }

    static JsonNode bundled() { return LOCAL.deepCopy(); }

    private static JsonNode load() {
        try (InputStream input = new ClassPathResource(
                "tokendance/catalog/incompatibility-policy.json").getInputStream()) {
            JsonNode policy = new ObjectMapper().readTree(input);
            if (policy.path("schemaVersion").asInt() != 1 || !policy.path("models").isObject()) {
                throw new IllegalStateException("兼容策略格式无效");
            }
            return policy;
        } catch (Exception exception) {
            // 不允许文件损坏时悄悄退化为允许导入。
            throw new IllegalStateException("兼容策略加载失败", exception);
        }
    }
}
