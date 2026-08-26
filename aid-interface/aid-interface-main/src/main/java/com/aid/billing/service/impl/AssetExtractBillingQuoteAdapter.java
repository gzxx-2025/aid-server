package com.aid.billing.service.impl;

import org.springframework.stereotype.Component;

import com.aid.billing.service.BusinessBillingQuoteAdapter;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.common.exception.ServiceException;
import com.aid.rps.dto.AssetExtractRequest;
import com.aid.rps.service.IAssetExtractService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

/** 角色、场景、道具提取的逐模型逐调用报价适配器。 */
@Component
@RequiredArgsConstructor
public class AssetExtractBillingQuoteAdapter implements BusinessBillingQuoteAdapter
{
    private final ObjectMapper objectMapper;
    private final IAssetExtractService assetExtractService;
    private final Validator validator;

    @Override
    public boolean supports(String quoteType)
    {
        return "ASSET_EXTRACT".equals(quoteType);
    }

    @Override
    public BillingQuoteVO quote(String quoteType, JsonNode payload, Long userId)
    {
        try
        {
            AssetExtractRequest request = objectMapper.treeToValue(payload, AssetExtractRequest.class);
            ConstraintViolation<AssetExtractRequest> violation = validator.validate(request).stream()
                    .sorted(java.util.Comparator.comparing(item -> item.getPropertyPath().toString()))
                    .findFirst().orElse(null);
            if (violation != null)
            {
                throw new ServiceException(violation.getMessage());
            }
            return assetExtractService.quoteExtractAssets(request, userId);
        }
        catch (JsonProcessingException e)
        {
            throw new ServiceException("报价参数无效");
        }
    }
}
