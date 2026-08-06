package com.aid.media.provider.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.media.dto.MediaVideoGenerateRequest;

class ViduVideoProviderClientSubtitleGuardTest {

    @Test
    void shouldAppendDialogueCaptionGuardOnceForAudioVideo() {
        ViduVideoProviderClient client = new ViduVideoProviderClient();
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setAudio(true);
        request.setPrompt("张叔担忧地说：\"查出指标异常后？\"");

        ReflectionTestUtils.invokeMethod(client, "applyDialogueCaptionGuard", request);
        String guardedPrompt = request.getPrompt();
        ReflectionTestUtils.invokeMethod(client, "applyDialogueCaptionGuard", request);

        assertTrue(guardedPrompt.contains("禁止把台词、说话人姓名或人物对白渲染成字幕"));
        assertEquals(guardedPrompt, request.getPrompt());
    }
}
