package com.aid.config.test;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aid.aid.service.IAidConfigService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 测试 payload 密钥脱敏回写解析器。
 * 后台「配置中心」列表对密钥/令牌类字段做了脱敏展示（{@code 前4+****+后4}，见 AidConfigController），
 * 因此页面再点「测试连接」时回传的就是脱敏串（如 {@code AKID****1Lez}），各 Tester 若直接拿去鉴权会失败。
 * 本解析器在分发给 Tester 前，把 payload 中「与已保存值的脱敏形态完全一致」（或为空）的密钥字段，
 * 还原为数据库里的真实值；用户重新输入的新值不等于脱敏串、不会被覆盖。统一收口，所有 Tester 受益。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestPayloadSecretResolver {

    private final IAidConfigService aidConfigService;

    /** testKey → aid_config 分类（与前端 CONFIG_TEST_KEY 反向一致；无密钥的 ai-model/ai-provider 不在内）。 */
    private static final Map<String, String> TESTKEY_TO_CATEGORY = Map.of(
            "alipay", "alipay",
            "smtp", "mail",
            "oss", "oss",
            "sms", "sms",
            "wxpay", "wxpay",
            "image-moderation", "image_moderation");

    /** 密钥/令牌类字段关键字（与 AidConfigController.SECRET_NAME_KEYWORDS 保持一致）。 */
    private static final List<String> SECRET_KEYWORDS = List.of(
            "secret", "password", "passwd", "pwd", "accesskey", "apikey", "api_key",
            "privatekey", "private_key", "token", "appsecret", "signkey", "mchkey");

    /**
     * 就地还原 payload 中被脱敏的密钥字段为数据库真实值。
     *
     * @param testKey 测试类型
     * @param payload 临时配置（页面回传，可能含脱敏密钥）
     */
    public void restoreMaskedSecrets(String testKey, Map<String, Object> payload) {
        if (StrUtil.isBlank(testKey) || payload == null || payload.isEmpty()) {
            return;
        }
        String category = TESTKEY_TO_CATEGORY.get(testKey);
        if (category == null) {
            // 无密钥语义的测试（ai-model/ai-provider 等）无需处理
            return;
        }
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!(entry.getValue() instanceof String submitted)) {
                continue;
            }
            String configName = entry.getKey();
            if (!isSecret(configName)) {
                continue;
            }
            String stored;
            try {
                stored = aidConfigService.getConfigValue(category, configName);
            } catch (Exception e) {
                // 读取失败不阻断：保持原值，由 Tester 凭证校验兜底
                log.warn("还原测试密钥读取配置失败, category={}, configName={}, err={}", category, configName, e.getMessage());
                continue;
            }
            if (StrUtil.isBlank(stored)) {
                continue;
            }
            // 提交值为空，或与已保存值的脱敏形态完全一致 → 视为未修改，还原真实值；新输入值不会命中
            if (StrUtil.isBlank(submitted) || submitted.equals(maskSecretValue(stored))) {
                entry.setValue(stored);
            }
        }
    }

    /** 是否密钥/令牌类字段（按 config_name 关键字，大小写不敏感；与列表脱敏口径一致）。 */
    private boolean isSecret(String configName) {
        if (StrUtil.isBlank(configName)) {
            return false;
        }
        String lower = configName.trim().toLowerCase();
        for (String kw : SECRET_KEYWORDS) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** 与 AidConfigController.maskSecretValue 完全一致的脱敏算法，用于精确比对「是否未修改」。 */
    private String maskSecretValue(String value) {
        if (StrUtil.isBlank(value)) {
            return value;
        }
        if (value.length() > 8) {
            return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
        }
        return "****";
    }
}
