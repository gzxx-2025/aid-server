package com.aid.media.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;

class MediaParentTaskSerializationTest {

    @Test
    void shouldPersistParentTaskIdInAudioRequestSnapshot() {
        MediaAudioGenerateRequest request = new MediaAudioGenerateRequest();
        request.setTtsText("测试");
        request.setVoiceCode("voice-1");
        request.setParentTaskId(3486L);

        String json = MediaTaskPayloadSanitizer.serializeRequest(request);

        assertTrue(json.contains("\"parentTaskId\":3486"));
    }

    @Test
    void shouldPersistParentTaskIdInVideoRequestSnapshot() {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setPrompt("对口型");
        request.setParentTaskId(3486L);

        String json = MediaTaskPayloadSanitizer.serializeRequest(request);

        assertTrue(json.contains("\"parentTaskId\":3486"));
    }
}
