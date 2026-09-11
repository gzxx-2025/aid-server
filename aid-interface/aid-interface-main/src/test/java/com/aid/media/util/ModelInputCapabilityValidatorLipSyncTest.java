package com.aid.media.util;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelInputCapabilityValidatorLipSyncTest {

    @Test
    void acceptsAudioDrivenAndTextDrivenLipSyncButRejectsMissingDriver() {
        AiModelConfigVo config = lipSyncConfig();

        MediaVideoGenerateRequest audio = request(null,
                Map.of("video_url", "video.mp4", "audio_url", "audio.mp3"));
        assertDoesNotThrow(() -> ModelInputCapabilityValidator.validateVideoQuote(config, audio, false));

        MediaVideoGenerateRequest text = request("你好，世界", Map.of("video_url", "video.mp4"));
        assertDoesNotThrow(() -> ModelInputCapabilityValidator.validateVideoQuote(config, text, false));

        MediaVideoGenerateRequest missing = request(null, Map.of("video_url", "video.mp4"));
        assertThrows(ServiceException.class,
                () -> ModelInputCapabilityValidator.validateVideoQuote(config, missing, false));
    }

    private AiModelConfigVo lipSyncConfig() {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setCapabilityCode("lip_sync");
        config.setGenerateMode("lip_sync");
        config.setSupportsTextInput(true);
        config.setCapabilityJson("{\"lipSync\":true,\"driveModes\":[\"audio\",\"text\"],"
                + "\"sceneRules\":{\"videoToVideo\":{}}}");
        return config;
    }

    private MediaVideoGenerateRequest request(String prompt, Map<String, Object> options) {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setCapabilityCode("lip_sync");
        request.setPrompt(prompt);
        request.setOptions(options);
        return request;
    }
}
