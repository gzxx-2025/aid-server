package com.aid.media.cleanup.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.aid.service.IAidAudioRecordService;
import com.aid.aid.service.IAidComicAssetService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.aid.service.IAidUserComicAssetService;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.util.MediaUrlResolver;

@ExtendWith(MockitoExtension.class)
class MediaOssCleanupServiceImplTest
{
    @Mock
    private OssTemplate ossTemplate;

    @Mock
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    @Mock
    private IAidUserComicAssetService aidUserComicAssetService;

    @Mock
    private IAidComicAssetService aidComicAssetService;

    @Mock
    private IAidAiVoiceLibraryService aidAiVoiceLibraryService;

    @Mock
    private IAidGenRecordService aidGenRecordService;

    @Mock
    private IAidAudioRecordService aidAudioRecordService;

    @Mock
    private MediaUrlResolver mediaUrlResolver;

    @InjectMocks
    private MediaOssCleanupServiceImpl service;

    @BeforeEach
    void runCleanupTaskInline()
    {
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(threadPoolTaskExecutor).execute(any(Runnable.class));
        when(mediaUrlResolver.toRelativePath(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mediaUrlResolver.toFullUrl(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldKeepFileWhenAnotherGeneratedRecordStillReferencesIt()
    {
        when(aidGenRecordService.count(any())).thenReturn(1L);

        service.cleanupFiles(Collections.singletonList("/shared/video.mp4"));

        verify(ossTemplate, never()).deleteByUrl(anyString());
    }

    @Test
    void shouldDeleteFileWhenNoBusinessReferenceRemains()
    {
        when(aidGenRecordService.count(any())).thenReturn(0L);
        when(aidAudioRecordService.count(any())).thenReturn(0L);
        when(aidUserComicAssetService.count(any())).thenReturn(0L);
        when(aidComicAssetService.count(any())).thenReturn(0L);
        when(aidAiVoiceLibraryService.count(any())).thenReturn(0L);
        when(ossTemplate.deleteByUrl("/orphan/video.mp4")).thenReturn(true);

        service.cleanupFiles(Collections.singletonList("/orphan/video.mp4"));

        verify(ossTemplate).deleteByUrl("/orphan/video.mp4");
    }
}
