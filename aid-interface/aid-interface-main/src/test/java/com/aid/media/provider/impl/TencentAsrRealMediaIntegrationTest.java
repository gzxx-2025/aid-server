package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.compose.config.TencentAsrConfigManager;
import com.aid.compose.domain.TimedSubtitleCue;
import com.aid.media.dto.SpeechRecognitionResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用线上真实媒体诊断腾讯云录音文件识别结果，未配置环境变量时自动跳过。 */
@Tag("integration")
class TencentAsrRealMediaIntegrationTest {

    private static final String DEFAULT_MEDIA_URL =
            "https://cdn.aidstudio.com.cn/aid/2026/08/06/c47780c092244df38c76c2673370a88f.mp4";
    private static final int MAX_RAW_TEXT_LOG_LENGTH = 500;

    @Test
    void shouldRecognizeStoryboard4605SpeechAndTimestampCues() {
        String secretId = System.getenv("TENCENT_ASR_SECRET_ID");
        String secretKey = System.getenv("TENCENT_ASR_SECRET_KEY");
        Assumptions.assumeTrue(StrUtil.isNotBlank(secretId) && StrUtil.isNotBlank(secretKey),
                "缺少腾讯云ASR环境变量，跳过真实媒体测试");

        String region = StrUtil.blankToDefault(System.getenv("TENCENT_ASR_REGION"), "ap-guangzhou");
        String engine = StrUtil.blankToDefault(
                System.getenv("TENCENT_ASR_ENGINE_MODEL"), "16k_zh");
        String mediaUrl = StrUtil.blankToDefault(System.getenv("TENCENT_ASR_MEDIA_URL"), DEFAULT_MEDIA_URL);
        TencentAsrSpeechRecognitionClient client = buildClient(secretId, secretKey, region, engine);

        SpeechRecognitionResult result = client.recognize(mediaUrl);
        assertNotNull(result, "腾讯云ASR响应不能为空");
        List<TimedSubtitleCue> cues = Objects.isNull(result.getCues()) ? List.of() : result.getCues();
        String rawText = StrUtil.blankToDefault(result.getText(), "");
        String rawTextPreview = StrUtil.subPre(rawText, MAX_RAW_TEXT_LOG_LENGTH);
        System.out.printf("腾讯云ASR真实媒体诊断: engine=%s, duration=%s, rawTextLength=%d, cueCount=%d, rawText=%s%n",
                engine, result.getDurationSeconds(), rawText.length(), cues.size(), rawTextPreview);

        assertAll(
                () -> assertTrue(Objects.nonNull(result.getDurationSeconds())
                                && result.getDurationSeconds() > 0D,
                        "腾讯云未返回有效音频时长"),
                () -> assertFalse(StrUtil.isBlank(rawText),
                        "视频有音轨，但腾讯云未识别出任何文本"),
                () -> assertFalse(cues.isEmpty(),
                        "腾讯云未返回可用时间戳字幕，请检查ResultDetail和原始Result")
        );
    }

    private TencentAsrSpeechRecognitionClient buildClient(String secretId, String secretKey,
                                                           String region, String engine) {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfigValues(TencentAsrConfigManager.CATEGORY)).thenReturn(Map.of(
                "enabled", "true",
                "secretId", secretId,
                "secretKey", secretKey,
                "region", region,
                "engineModelType", engine,
                "sentenceMaxLength", "10",
                "speakerDiarization", "0",
                "timeoutSeconds", "180",
                "maxAttempts", "1"
        ));
        TencentAsrConfigManager configManager = new TencentAsrConfigManager(configService);
        return new TencentAsrSpeechRecognitionClient(configManager);
    }
}
