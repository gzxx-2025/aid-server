package com.aid.billing.service.impl;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.aid.billing.service.BusinessBillingQuoteAdapter;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.common.exception.ServiceException;
import com.aid.rps.dto.FormCardImageGenerateRequest;
import com.aid.rps.dto.FormEditChatImageGenerateRequest;
import com.aid.rps.dto.FormImageGenerateRequest;
import com.aid.rps.dto.FormGenerateRequest;
import com.aid.rps.dto.FormMultiViewImageGenerateRequest;
import com.aid.rps.dto.RpsFormImageUpscaleRequest;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.service.IFormEditChatImageService;
import com.aid.rps.service.IFormMultiViewImageService;
import com.aid.rps.service.IRpsFormImageBusinessService;
import com.aid.storyboard.dto.StoryboardEditImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardImageUpscaleRequest;
import com.aid.storyboard.dto.StoryboardMultiViewGridImageGenerateRequest;
import com.aid.storyboard.service.IStoryboardEditImageService;
import com.aid.storyboard.service.IStoryboardImageUpscaleService;
import com.aid.storyboard.service.IStoryboardMultiViewGridImageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

/** 编辑、高清、机位、角色卡与基础形态图的真实业务报价适配器。 */
@Component
@RequiredArgsConstructor
public class ImageOperationBillingQuoteAdapter implements BusinessBillingQuoteAdapter
{
    private static final Set<String> SUPPORTED = Set.of(
            "STORYBOARD_EDIT_IMAGE", "STORYBOARD_IMAGE_UPSCALE",
            "STORYBOARD_MULTI_VIEW_IMAGE", "STORYBOARD_MULTI_GRID_IMAGE",
            "FORM_EDIT_CHAT_IMAGE", "FORM_MULTI_VIEW_IMAGE",
            "FORM_GENERATE", "FORM_IMAGE_UPSCALE", "FORM_IMAGE", "FORM_CARD_IMAGE");

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final IStoryboardEditImageService storyboardEditImageService;
    private final IStoryboardImageUpscaleService storyboardImageUpscaleService;
    private final IStoryboardMultiViewGridImageService storyboardMultiViewGridImageService;
    private final IFormEditChatImageService formEditChatImageService;
    private final IFormMultiViewImageService formMultiViewImageService;
    private final IRpsFormImageBusinessService rpsFormImageBusinessService;
    private final IAssetExtractService assetExtractService;

    @Override
    public boolean supports(String quoteType)
    {
        return SUPPORTED.contains(quoteType);
    }

    @Override
    public BillingQuoteVO quote(String quoteType, JsonNode payload, Long userId)
    {
        return switch (quoteType)
        {
            case "STORYBOARD_EDIT_IMAGE" -> storyboardEditImageService.quoteEditImage(
                    convertAndValidate(payload, StoryboardEditImageGenerateRequest.class), userId);
            case "STORYBOARD_IMAGE_UPSCALE" -> storyboardImageUpscaleService.quoteUpscaleImage(
                    convertAndValidate(payload, StoryboardImageUpscaleRequest.class), userId);
            case "STORYBOARD_MULTI_VIEW_IMAGE", "STORYBOARD_MULTI_GRID_IMAGE" ->
                    quoteStoryboardMultiView(quoteType, payload, userId);
            case "FORM_EDIT_CHAT_IMAGE" -> formEditChatImageService.quoteEditChatImage(
                    convertAndValidate(payload, FormEditChatImageGenerateRequest.class), userId);
            case "FORM_MULTI_VIEW_IMAGE" -> formMultiViewImageService.quoteMultiViewImage(
                    convertAndValidate(payload, FormMultiViewImageGenerateRequest.class), userId);
            case "FORM_IMAGE_UPSCALE" -> rpsFormImageBusinessService.quoteUpscaleImage(
                    convertAndValidate(payload, RpsFormImageUpscaleRequest.class), userId);
            case "FORM_GENERATE" -> assetExtractService.quoteFormGenerate(
                    convertAndValidate(payload, FormGenerateRequest.class), userId);
            case "FORM_IMAGE" -> assetExtractService.quoteFormImage(
                    convertAndValidate(payload, FormImageGenerateRequest.class), userId);
            case "FORM_CARD_IMAGE" -> assetExtractService.quoteCardImage(
                    convertAndValidate(payload, FormCardImageGenerateRequest.class), userId);
            default -> throw new ServiceException("报价类型不支持");
        };
    }

    private BillingQuoteVO quoteStoryboardMultiView(
            String quoteType, JsonNode payload, Long userId)
    {
        StoryboardMultiViewGridImageGenerateRequest request = convertAndValidate(
                payload, StoryboardMultiViewGridImageGenerateRequest.class);
        int count = request.getAngles() == null ? 0 : request.getAngles().size();
        if (("STORYBOARD_MULTI_VIEW_IMAGE".equals(quoteType) && count != 1)
                || ("STORYBOARD_MULTI_GRID_IMAGE".equals(quoteType) && count != 9))
        {
            throw new ServiceException("报价类型不匹配");
        }
        return storyboardMultiViewGridImageService.quoteMultiViewGridImage(request, userId);
    }

    private <T> T convertAndValidate(JsonNode payload, Class<T> type)
    {
        try
        {
            T value = objectMapper.treeToValue(payload, type);
            ConstraintViolation<T> violation = validator.validate(value).stream()
                    .sorted(java.util.Comparator.comparing(item -> item.getPropertyPath().toString()))
                    .findFirst().orElse(null);
            if (violation != null)
            {
                throw new ServiceException(violation.getMessage());
            }
            return value;
        }
        catch (JsonProcessingException e)
        {
            throw new ServiceException("报价参数无效");
        }
    }
}
