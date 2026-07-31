package com.aid.compose.service.impl;

import com.aid.aid.service.IAidConfigService;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.compose.config.TencentAsrConfigManager;
import com.aid.compose.config.TencentAsrProperties;
import com.aid.compose.domain.TimedSubtitleCue;
import com.aid.compose.dto.TencentAsrTestRequest;
import com.aid.compose.dto.TencentAsrTestResult;
import com.aid.media.dto.SpeechRecognitionResult;
import com.aid.media.provider.impl.TencentAsrSpeechRecognitionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TencentAsrAdminConfigServiceImplTest {

    @Mock
    private ConfigService configService;
    @Mock
    private IAidConfigService aidConfigService;
    @Mock
    private TencentAsrConfigManager configManager;
    @Mock
    private OssTemplate ossTemplate;
    @Mock
    private MediaUrlResolver mediaUrlResolver;
    @Mock
    private TencentAsrSpeechRecognitionClient recognitionClient;

    private TencentAsrAdminConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TencentAsrAdminConfigServiceImpl(configService, aidConfigService, configManager,
                ossTemplate, mediaUrlResolver, recognitionClient);
    }

    @Test
    void shouldReturnFinalResultAndDeleteTemporaryVideo() {
        mockConfiguredCredentials();
        MockMultipartFile file = videoFile();
        TencentAsrTestRequest request = request(file);
        SpeechRecognitionResult recognition = recognitionResult();
        when(ossTemplate.upload(file, "asr-test")).thenReturn("/asr-test/test.mp4");
        when(mediaUrlResolver.toFullUrl("/asr-test/test.mp4")).thenReturn("https://cdn.test/asr-test/test.mp4");
        when(mediaUrlResolver.toProviderUrl("https://cdn.test/asr-test/test.mp4"))
                .thenReturn("https://cos.test/asr-test/test.mp4");
        when(recognitionClient.recognizeForTest("https://cos.test/asr-test/test.mp4"))
                .thenReturn(recognition);
        when(ossTemplate.deleteByUrl("/asr-test/test.mp4")).thenReturn(true);

        TencentAsrTestResult result = service.test(request);

        assertEquals("第一句\n第二句", result.getText());
        assertEquals(2, result.getCueCount());
        assertEquals(3.2D, result.getDurationSeconds());
        verify(configManager).refresh();
        verify(ossTemplate).deleteByUrl("/asr-test/test.mp4");
    }

    @Test
    void shouldDeleteTemporaryVideoWhenRecognitionFails() {
        mockConfiguredCredentials();
        MockMultipartFile file = videoFile();
        when(ossTemplate.upload(file, "asr-test")).thenReturn("/asr-test/test.mp4");
        when(mediaUrlResolver.toFullUrl("/asr-test/test.mp4")).thenReturn("https://cdn.test/asr-test/test.mp4");
        when(mediaUrlResolver.toProviderUrl("https://cdn.test/asr-test/test.mp4"))
                .thenReturn("https://cos.test/asr-test/test.mp4");
        when(recognitionClient.recognizeForTest("https://cos.test/asr-test/test.mp4"))
                .thenThrow(new ServiceException("识别测试失败"));
        when(ossTemplate.deleteByUrl("/asr-test/test.mp4")).thenReturn(true);

        assertThrows(ServiceException.class, () -> service.test(request(file)));

        verify(ossTemplate).deleteByUrl("/asr-test/test.mp4");
    }

    @Test
    void shouldRejectMissingCredentialsBeforeUpload() {
        TencentAsrProperties properties = new TencentAsrProperties();
        when(configManager.getProperties()).thenReturn(properties);

        assertThrows(ServiceException.class, () -> service.test(request(videoFile())));

        verify(ossTemplate, never()).upload(any(), anyString());
    }

    private TencentAsrTestRequest request(MockMultipartFile file) {
        TencentAsrTestRequest request = new TencentAsrTestRequest();
        request.setFile(file);
        return request;
    }

    private void mockConfiguredCredentials() {
        TencentAsrProperties properties = new TencentAsrProperties();
        properties.setSecretId("test-secret-id");
        properties.setSecretKey("test-secret-key");
        when(configManager.getProperties()).thenReturn(properties);
    }

    private MockMultipartFile videoFile() {
        return new MockMultipartFile("file", "test.mp4", "video/mp4", new byte[]{1, 2, 3});
    }

    private SpeechRecognitionResult recognitionResult() {
        TimedSubtitleCue first = new TimedSubtitleCue();
        first.setStartSeconds(0D);
        first.setEndSeconds(1.5D);
        first.setText("第一句");
        TimedSubtitleCue second = new TimedSubtitleCue();
        second.setStartSeconds(1.8D);
        second.setEndSeconds(3.2D);
        second.setText("第二句");
        SpeechRecognitionResult result = new SpeechRecognitionResult();
        result.setText("腾讯云原始结果");
        result.setDurationSeconds(3.2D);
        result.setCues(List.of(first, second));
        return result;
    }
}
