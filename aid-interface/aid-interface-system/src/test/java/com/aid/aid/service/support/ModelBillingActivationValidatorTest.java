package com.aid.aid.service.support;

import com.aid.aid.domain.AidAiModel;
import com.aid.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelBillingActivationValidatorTest {

    @Test
    void nonMarkedHistoricalModelIsUnaffected() {
        AidAiModel model = model("0", "{}", "SKU", "{\"skus\":[]}");
        assertDoesNotThrow(() -> ModelBillingActivationValidator.validateIfRequiredAndEnabled(model));
    }

    @Test
    void markedDisabledModelCanBeSavedWithoutPrice() {
        AidAiModel model = model("1", "{\"requiresConfiguredBilling\":true}", "SKU", "{\"skus\":[]}");
        assertDoesNotThrow(() -> ModelBillingActivationValidator.validateIfRequiredAndEnabled(model));
    }

    @Test
    void markedModelCannotBeEnabledWithoutPrice() {
        AidAiModel model = model("0", "{\"requiresConfiguredBilling\":true}", "SKU", "{\"skus\":[]}");
        assertThrows(ServiceException.class, () -> ModelBillingActivationValidator.validateIfRequiredAndEnabled(model));
    }

    @Test
    void enabledMarkedModelCannotClearOrDisableAllSkus() {
        AidAiModel model = model("0", "{\"requiresConfiguredBilling\":true}", "SKU",
            "{\"skus\":[{\"enabled\":true,\"pricePerSecond\":0.2}]}");
        assertDoesNotThrow(() -> ModelBillingActivationValidator.validateIfRequiredAndEnabled(model));
        model.setBillingRuleJson("{\"skus\":[{\"enabled\":false,\"pricePerSecond\":0.2}]}");
        assertThrows(ServiceException.class, () -> ModelBillingActivationValidator.validateIfRequiredAndEnabled(model));
    }

    @Test
    void protectedModelCannotRemoveCapabilityGuardDuringUpdate() {
        AidAiModel previous = model("1", "{\"requiresConfiguredBilling\":true}", "SKU", "{\"skus\":[]}");
        AidAiModel effective = model("0", "{\"requiresConfiguredBilling\":false}", "SKU", "{\"skus\":[]}");

        assertThrows(ServiceException.class, () -> ModelBillingActivationValidator.validateUpdate(previous, effective));
    }

    @Test
    void inputMediaAddonCannotPretendToBeMainGenerationPrice() {
        AidAiModel model = model("0", "{\"requiresConfiguredBilling\":true}", "SKU",
            "{\"skus\":[{\"enabled\":true,\"inputPricing\":{\"image\":{\"unitPrice\":0.2}}}]}" );

        assertThrows(ServiceException.class, () -> ModelBillingActivationValidator.validateIfRequiredAndEnabled(model));
        model.setBillingRuleJson("{\"skus\":[{\"enabled\":true,\"pricePerSecond\":0.2,"
            + "\"inputPricing\":{\"image\":{\"unitPrice\":0.1}}}]}" );
        assertDoesNotThrow(() -> ModelBillingActivationValidator.validateIfRequiredAndEnabled(model));
    }

    private AidAiModel model(String status, String capability, String mode, String rule) {
        AidAiModel model = new AidAiModel();
        model.setStatus(status);
        model.setCapabilityJson(capability);
        model.setBillingMode(mode);
        model.setBillingRuleJson(rule);
        return model;
    }
}
