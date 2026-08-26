package com.aid.billing.service.impl;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.aid.billing.service.BusinessBillingQuoteAdapter;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.common.exception.ServiceException;
import com.aid.storyboard.dto.StoryboardImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoEdgeGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoFromImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoGridGenerateRequest;
import com.aid.storyboard.service.IStoryboardImageGenerationService;
import com.aid.storyboard.service.IStoryboardVideoGenerationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

/** 分镜图片与四种视频出片业务请求的统一媒体报价适配器。 */
@Component
@RequiredArgsConstructor
public class StoryboardMediaBillingQuoteAdapter implements BusinessBillingQuoteAdapter
{
    private static final Set<String> SUPPORTED = Set.of(
            "STORYBOARD_IMAGE", "STORYBOARD_VIDEO", "STORYBOARD_VIDEO_IMAGE",
            "STORYBOARD_VIDEO_GRID", "STORYBOARD_VIDEO_EDGE");

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final IStoryboardImageGenerationService imageGenerationService;
    private final IStoryboardVideoGenerationService videoGenerationService;

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
            case "STORYBOARD_IMAGE" -> imageGenerationService.quoteImage(
                    convertAndValidate(payload, StoryboardImageGenerateRequest.class), userId);
            case "STORYBOARD_VIDEO" -> videoGenerationService.quoteVideo(
                    convertAndValidate(payload, StoryboardVideoGenerateRequest.class), userId);
            case "STORYBOARD_VIDEO_IMAGE" -> videoGenerationService.quoteVideoFromImage(
                    convertAndValidate(payload, StoryboardVideoFromImageGenerateRequest.class), userId);
            case "STORYBOARD_VIDEO_GRID" -> videoGenerationService.quoteVideoFromGrid(
                    convertAndValidate(payload, StoryboardVideoGridGenerateRequest.class), userId);
            case "STORYBOARD_VIDEO_EDGE" -> videoGenerationService.quoteVideoFromEdge(
                    convertAndValidate(payload, StoryboardVideoEdgeGenerateRequest.class), userId);
            default -> throw new ServiceException("报价类型不支持");
        };
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
