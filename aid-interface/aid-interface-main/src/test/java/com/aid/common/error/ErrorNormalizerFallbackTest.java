package com.aid.common.error;

import com.aid.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorNormalizerFallbackTest
{
    @Test
    void shouldClassifyKnownModelParameterErrors()
    {
        TaskErrorResult result = ErrorNormalizer.classifyFallback(
                "start_image parameter is required");

        assertEquals(TaskErrorCode.MODEL_PARAMETER_INCOMPATIBLE.name(), result.getErrorCode());
    }

    @Test
    void shouldClassifyOpenAiImageSizeErrors()
    {
        TaskErrorResult result = ErrorNormalizer.classifyFallback(
                "Both edges must be multiples of 16 pixels");

        assertEquals(TaskErrorCode.USER_IMAGE_RESOLUTION_INVALID.name(), result.getErrorCode());
        assertEquals("图片尺寸不符合要求，更换后重试", result.getUserMessage());
    }

    @Test
    void shouldClassifyOpenAiImageFormatAndPayloadErrors()
    {
        TaskErrorResult formatResult = ErrorNormalizer.classifyFallback(
                "Unsupported image format. Please use PNG, JPEG, WEBP, or GIF");
        TaskErrorResult sizeResult = ErrorNormalizer.classifyFallback(
                "Request entity too large");

        assertEquals(TaskErrorCode.USER_FILE_FORMAT_INVALID.name(), formatResult.getErrorCode());
        assertEquals(TaskErrorCode.USER_FILE_TOO_LARGE.name(), sizeResult.getErrorCode());
    }

    @Test
    void shouldClassifyOpenAiUnsupportedOutputOptions()
    {
        TaskErrorResult result = ErrorNormalizer.classifyFallback(
                "Transparent backgrounds aren't supported for gpt-image-2");

        assertEquals(TaskErrorCode.MODEL_PARAMETER_INCOMPATIBLE.name(), result.getErrorCode());
    }

    @Test
    void shouldNotTreatGenericMissingFieldAsModelCapabilityError()
    {
        TaskErrorResult result = ErrorNormalizer.classifyFallback(
                "field is missing or empty");

        assertEquals(TaskErrorCode.AI_GENERATION_FAILED.name(), result.getErrorCode());
    }

    @Test
    void shouldClassifyOssPersistenceDetails()
    {
        TaskErrorResult result = ErrorNormalizer.classifyFallback(
                "OSS 持久化失败：SocketTimeoutException");

        assertEquals(TaskErrorCode.OSS_PERSIST_FAILED.name(), result.getErrorCode());
        assertEquals("生成结果保存失败，重新生成", result.getUserMessage());
    }

    @Test
    void shouldNotTreatProviderBalanceAsUserBalance()
    {
        TaskErrorResult result = ErrorNormalizer.normalize(
                new ServiceException("模型余额不足"));

        assertEquals(TaskErrorCode.PROVIDER_QUOTA_EXHAUSTED.name(), result.getErrorCode());
        assertEquals("当前生成服务暂不可用", result.getUserMessage());
    }

    @Test
    void shouldKeepExactBillingBalanceAsUserBalance()
    {
        TaskErrorResult result = ErrorNormalizer.normalize(
                new ServiceException("余额不足")
                        .setDetailMessage(TaskErrorCode.USER_BALANCE_NOT_ENOUGH.name()));

        assertEquals(TaskErrorCode.USER_BALANCE_NOT_ENOUGH.name(), result.getErrorCode());
    }

    @Test
    void shouldNotGuessUnmarkedBalanceAsUserBalance()
    {
        TaskErrorResult result = ErrorNormalizer.normalize(
                new ServiceException("余额不足"));

        assertEquals(TaskErrorCode.PROVIDER_QUOTA_EXHAUSTED.name(), result.getErrorCode());
    }

    @Test
    void shouldKeepBillingMarkerThroughWrappedException()
    {
        ServiceException billingException = new ServiceException("余额不足")
                .setDetailMessage(TaskErrorCode.USER_BALANCE_NOT_ENOUGH.name());
        TaskErrorResult result = ErrorNormalizer.normalize(
                new RuntimeException("预冻结失败", billingException));

        assertEquals(TaskErrorCode.USER_BALANCE_NOT_ENOUGH.name(), result.getErrorCode());
    }

    @Test
    void shouldProtectProviderOperationalMessages()
    {
        assertTrue(ErrorNormalizer.usesProtectedUserMessage(
                TaskErrorCode.PROVIDER_QUOTA_EXHAUSTED));
        assertTrue(ErrorNormalizer.usesProtectedUserMessage(
                TaskErrorCode.UPSTREAM_AUTH_INVALID));
        assertFalse(ErrorNormalizer.usesProtectedUserMessage(
                TaskErrorCode.UPSTREAM_CONTENT_FILTERED));
    }
}
