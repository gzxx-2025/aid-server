package com.aid.billing.service.impl;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.aid.billing.service.BusinessBillingQuoteAdapter;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.common.exception.ServiceException;
import com.aid.storyboard.dto.GenerateAudioRequest;
import com.aid.storyboard.dto.LipSyncRequest;
import com.aid.storyboard.dto.StoryboardAudioBatchRequest;
import com.aid.storyboard.dto.StoryboardLipSyncBatchRequest;
import com.aid.storyboard.service.IStoryboardAudioBatchService;
import com.aid.storyboard.service.IStoryboardLipSyncService;
import com.aid.storyboard.service.IStoryboardWorkbenchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

/** 单配音、批量配音和对口型复合报价适配器。 */
@Component
@RequiredArgsConstructor
public class StoryboardAudioBillingQuoteAdapter implements BusinessBillingQuoteAdapter
{
    private static final Set<String> SUPPORTED = Set.of(
            "STORYBOARD_AUDIO", "STORYBOARD_AUDIO_BATCH",
            "STORYBOARD_LIP_SYNC", "STORYBOARD_LIP_SYNC_BATCH");

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final IStoryboardWorkbenchService storyboardWorkbenchService;
    private final IStoryboardAudioBatchService storyboardAudioBatchService;
    private final IStoryboardLipSyncService storyboardLipSyncService;

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
            case "STORYBOARD_AUDIO" -> storyboardWorkbenchService.quoteAudio(
                    convertAndValidate(payload, GenerateAudioRequest.class), userId);
            case "STORYBOARD_AUDIO_BATCH" -> storyboardAudioBatchService.quoteBatchAudio(
                    convertAndValidate(payload, StoryboardAudioBatchRequest.class), userId);
            case "STORYBOARD_LIP_SYNC" -> storyboardLipSyncService.quoteLipSync(
                    convertAndValidate(payload, LipSyncRequest.class), userId);
            case "STORYBOARD_LIP_SYNC_BATCH" -> storyboardLipSyncService.quoteBatchLipSync(
                    convertAndValidate(payload, StoryboardLipSyncBatchRequest.class), userId);
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
