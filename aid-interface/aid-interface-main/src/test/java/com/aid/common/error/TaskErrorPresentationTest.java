package com.aid.common.error;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskErrorPresentationTest
{
    @Test
    void shouldKeepRawReasonOnlyInInternalDetail()
    {
        String rawMessage = "contain real person: provider response details";

        ServiceException exception = TaskErrorPresentation.toServiceException(
                rawMessage, "生成失败");

        assertEquals("更换参考图后重试", exception.getMessage());
        assertEquals(rawMessage, exception.getDetailMessage());
        assertTrue(exception.getMessage().length() <= 12);
    }

    @Test
    void shouldRankSpecificErrorsAboveGenericErrors()
    {
        TaskErrorResult generic = TaskErrorResult.of(TaskErrorCode.AI_GENERATION_FAILED);
        TaskErrorResult specific = TaskErrorResult.of(TaskErrorCode.REAL_PERSON_RESTRICTED);

        assertTrue(TaskErrorPresentation.specificity(specific)
                > TaskErrorPresentation.specificity(generic));
    }

    @Test
    void shouldNotSerializeRawProviderMessage() throws Exception
    {
        TaskErrorResult result = TaskErrorResult.of(
                TaskErrorCode.REAL_PERSON_RESTRICTED, "provider response details");

        String json = new ObjectMapper().writeValueAsString(result);

        assertFalse(json.contains("rawMessage"));
        assertFalse(json.contains("provider response details"));
    }

    @Test
    void shouldHandleMissingRawMessageAndLimitFallbackLength()
    {
        ServiceException exception = TaskErrorPresentation.toServiceException(
                null, "这是一个超过十二个字的业务异常兜底文案");

        assertEquals("这是一个超过十二个字的业", exception.getMessage());
        assertTrue(exception.getMessage().length() <= 12);
    }

    @Test
    void shouldReturnSafeProviderQuotaAndAuthenticationMessages()
    {
        ServiceException quotaException = TaskErrorPresentation.toServiceException(
                "insufficient credits: account balance 0", "生成失败");
        ServiceException authException = TaskErrorPresentation.toServiceException(
                "invalid api key: sk-provider-secret", "生成失败");

        assertEquals("模型额度不足", quotaException.getMessage());
        assertEquals("当前生成服务暂不可用", authException.getMessage());
        assertFalse(quotaException.getMessage().contains("account"));
        assertFalse(authException.getMessage().contains("认证"));
    }

    @Test
    void shouldSanitizeDirectAjaxProviderErrors()
    {
        AjaxResult result = AjaxResult.error("模型余额不足，请联系管理员");

        assertEquals("模型额度不足，请联系管理员", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void shouldReturnActionableShortMessagesForImageInputErrors()
    {
        ServiceException resolutionException = TaskErrorPresentation.toServiceException(
                "Both edges must be multiples of 16 pixels", "生成失败");
        ServiceException formatException = TaskErrorPresentation.toServiceException(
                "Unsupported image format", "生成失败");
        ServiceException payloadException = TaskErrorPresentation.toServiceException(
                "Request entity too large", "生成失败");

        assertEquals("图片尺寸不支持", resolutionException.getMessage());
        assertEquals("文件格式不支持", formatException.getMessage());
        assertEquals("文件过大，请压缩", payloadException.getMessage());
        assertTrue(resolutionException.getMessage().length() <= 12);
        assertTrue(formatException.getMessage().length() <= 12);
        assertTrue(payloadException.getMessage().length() <= 12);
    }
}
