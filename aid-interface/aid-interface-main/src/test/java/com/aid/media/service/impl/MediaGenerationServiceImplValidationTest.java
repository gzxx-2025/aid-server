package com.aid.media.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.enums.MediaType;

class MediaGenerationServiceImplValidationTest {

    @Test
    void acceptsMatchingAudioModel() {
        MediaGenerationServiceImpl service = mock(MediaGenerationServiceImpl.class, CALLS_REAL_METHODS);
        AiModelConfigVo model = model("audio");

        AiModelConfigVo result = ReflectionTestUtils.invokeMethod(
                service, "requireModelType", model, MediaType.AUDIO);

        assertEquals(model, result);
    }

    @Test
    void rejectsImageModelForAudioGeneration() {
        MediaGenerationServiceImpl service = mock(MediaGenerationServiceImpl.class, CALLS_REAL_METHODS);
        AiModelConfigVo model = model("image");

        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "requireModelType", model, MediaType.AUDIO));
    }

    private AiModelConfigVo model(String modelType) {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode("test-model");
        model.setModelType(modelType);
        return model;
    }
}
