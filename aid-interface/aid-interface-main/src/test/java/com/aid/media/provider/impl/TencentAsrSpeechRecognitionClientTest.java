package com.aid.media.provider.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.media.dto.SpeechRecognitionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TencentAsrSpeechRecognitionClientTest {

    private final TencentAsrSpeechRecognitionClient client = new TencentAsrSpeechRecognitionClient(null);

    @Test
    void shouldNormalizeTencentSentenceDetails() {
        JSONObject data = JSON.parseObject("""
                {
                  "TaskId": 9266418,
                  "AudioDuration": 2.38,
                  "Result": "[0:0.020,0:2.380] 腾讯云语音识别欢迎您。",
                  "ResultDetail": [{
                    "FinalSentence": "腾讯云语音识别欢迎您。",
                    "StartMs": 20,
                    "EndMs": 2380,
                    "SpeakerId": 0
                  }]
                }
                """);

        SpeechRecognitionResult result = client.normalizeResult(data);

        assertEquals(2.38D, result.getDurationSeconds());
        assertEquals(1, result.getCues().size());
        assertEquals(0.02D, result.getCues().get(0).getStartSeconds());
        assertEquals(2.38D, result.getCues().get(0).getEndSeconds());
        assertEquals("speaker_0", result.getCues().get(0).getSpeaker());
    }

    @Test
    void shouldFallbackToTimestampedPlainResultWhenDetailsMissing() {
        JSONObject data = new JSONObject();
        data.put("TaskId", 1);
        data.put("AudioDuration", 3.2D);
        data.put("Result", "[0:0.100,0:1.500] 第一段。\n[0:1.800,0:3.200] 第二段！");

        SpeechRecognitionResult result = client.normalizeResult(data);

        assertEquals(2, result.getCues().size());
        assertEquals("第一段。", result.getCues().get(0).getText());
        assertEquals(1.8D, result.getCues().get(1).getStartSeconds());
    }
}
