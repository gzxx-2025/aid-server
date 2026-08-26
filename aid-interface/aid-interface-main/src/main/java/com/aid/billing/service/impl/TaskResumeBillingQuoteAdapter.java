package com.aid.billing.service.impl;

import org.springframework.stereotype.Component;

import com.aid.billing.service.BusinessBillingQuoteAdapter;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.common.exception.ServiceException;
import com.aid.rps.dto.TaskResumeRequest;
import com.aid.rps.service.ITaskResumeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

/** 统一继续生成请求的无副作用报价适配器。 */
@Component
@RequiredArgsConstructor
public class TaskResumeBillingQuoteAdapter implements BusinessBillingQuoteAdapter
{
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final ITaskResumeService taskResumeService;

    @Override
    public boolean supports(String quoteType)
    {
        return "TASK_RESUME".equals(quoteType);
    }

    @Override
    public BillingQuoteVO quote(String quoteType, JsonNode payload, Long userId)
    {
        try
        {
            if (payload == null || !payload.isObject() || payload.size() != 1
                    || !payload.has("taskId"))
            {
                throw new ServiceException("报价参数无效");
            }
            TaskResumeRequest request = objectMapper.treeToValue(payload, TaskResumeRequest.class);
            ConstraintViolation<TaskResumeRequest> violation = validator.validate(request).stream()
                    .sorted(java.util.Comparator.comparing(item -> item.getPropertyPath().toString()))
                    .findFirst().orElse(null);
            if (violation != null)
            {
                throw new ServiceException(violation.getMessage());
            }
            return taskResumeService.quoteResume(request.getTaskId(), userId);
        }
        catch (JsonProcessingException e)
        {
            throw new ServiceException("报价参数无效");
        }
    }
}
