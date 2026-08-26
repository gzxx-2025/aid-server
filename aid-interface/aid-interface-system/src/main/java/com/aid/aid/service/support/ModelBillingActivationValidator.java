package com.aid.aid.service.support;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidAiModel;
import com.aid.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

/** 模型从停用切换为启用前的通用计费完整性闸门。 */
@Slf4j
public final class ModelBillingActivationValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelBillingActivationValidator() {
    }

    /** 仅显式声明该通用能力位的模型启用此严格闸门，避免改变历史模型语义。 */
    public static boolean isRequired(AidAiModel model) {
        if (model == null || StrUtil.isBlank(model.getCapabilityJson())) {
            return false;
        }
        try {
            return MAPPER.readTree(model.getCapabilityJson()).path("requiresConfiguredBilling").asBoolean(false);
        } catch (Exception ex) {
            return false;
        }
    }

    public static void validateIfRequiredAndEnabled(AidAiModel model) {
        if (model != null && "0".equals(model.getStatus()) && isRequired(model)) {
            validate(model);
        }
    }

    /** 已受保护模型不能通过同次编辑删除能力位来绕过启用校验。 */
    public static void validateUpdate(AidAiModel previous, AidAiModel effective) {
        boolean protectedBefore = isRequired(previous);
        boolean protectedAfter = isRequired(effective);
        if (protectedBefore && !protectedAfter) {
            throw failure("billing protection removed, modelCode=" + previous.getModelCode(), "计费保护不可关闭");
        }
        if (effective != null && "0".equals(effective.getStatus()) && (protectedBefore || protectedAfter)) {
            validate(effective);
        }
    }

    public static void validate(AidAiModel model) {
        if (model == null) {
            throw failure("null model", "模型配置为空");
        }
        String mode = StrUtil.blankToDefault(model.getBillingMode(), "FIXED").trim().toUpperCase();
        if ("FIXED".equals(mode)) {
            if (model.getCostCredits() == null || model.getCostCredits().compareTo(BigDecimal.ZERO) <= 0) {
                throw failure("fixed price missing, modelCode=" + model.getModelCode(), "模型价格未配置");
            }
            return;
        }
        if (!"SKU".equals(mode)) {
            throw failure("invalid billing mode=" + mode, "计费模式无效");
        }
        ModelBillingRuleValidator.validate(model);
    }

    private static ServiceException failure(String reason, String clientMessage) {
        log.warn("Model billing activation rejected: {}", reason);
        return new ServiceException(clientMessage);
    }

}
