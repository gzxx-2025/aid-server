package com.aid.storyboard.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class GenerateAudioRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void acceptsSupportedAudioParameters() {
        GenerateAudioRequest request = baseRequest();
        request.setEmotionScale(5);
        request.setSpeechRate(-50);
        request.setLoudnessRate(100);
        request.setPitch(12);
        request.setAudioFormat("ogg_opus");
        request.setSampleRate(24000);

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsOutOfRangeAudioParameters() {
        GenerateAudioRequest request = baseRequest();
        request.setEmotionScale(6);
        request.setSpeechRate(101);
        request.setLoudnessRate(-51);
        request.setPitch(13);
        request.setAudioFormat("aac");
        request.setSampleRate(96000);

        assertFalse(validator.validate(request).isEmpty());
    }

    private GenerateAudioRequest baseRequest() {
        GenerateAudioRequest request = new GenerateAudioRequest();
        request.setStoryboardId(1L);
        request.setTtsText("测试配音");
        return request;
    }
}
