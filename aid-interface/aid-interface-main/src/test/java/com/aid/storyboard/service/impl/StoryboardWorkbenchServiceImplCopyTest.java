package com.aid.storyboard.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.enums.ProjectTypeEnum;
import com.aid.step.service.ICreationStepService;
import com.aid.storyboard.dto.StoryboardCreateRequest;
import com.aid.storyboard.vo.StoryboardVO;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

@ExtendWith(MockitoExtension.class)
class StoryboardWorkbenchServiceImplCopyTest
{
    @BeforeAll
    static void initTableInfo()
    {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "storyboard-copy-test");
        assistant.setCurrentNamespace("storyboard-copy-test");
        TableInfoHelper.initTableInfo(assistant, AidGenRecord.class);
    }

    @Mock
    private IAidStoryboardService aidStoryboardService;

    @Mock
    private IAidGenRecordService aidGenRecordService;

    @Mock
    private IAidComicProjectService aidComicProjectService;

    @Mock
    private IAidComicEpisodeService aidComicEpisodeService;

    @Mock
    private ICreationStepService creationStepService;

    @InjectMocks
    private StoryboardWorkbenchServiceImpl service;

    @Test
    void shouldAtomicallyCopyStoryboardContentAndOriginalMedia()
    {
        long userId = 61L;
        AidComicProject project = new AidComicProject();
        project.setProjectType(ProjectTypeEnum.MOVIE.getValue());
        when(aidComicProjectService.getOne(any())).thenReturn(project);
        when(aidStoryboardService.getOne(any())).thenReturn(null);

        AidStoryboard source = sourceStoryboard(userId);
        when(aidStoryboardService.getById(4565L)).thenReturn(source);
        when(aidStoryboardService.save(any(AidStoryboard.class))).thenAnswer(invocation -> {
            AidStoryboard saved = invocation.getArgument(0);
            saved.setId(4818L);
            return true;
        });
        when(aidStoryboardService.updateById(any(AidStoryboard.class))).thenReturn(true);

        AidGenRecord sourceImage = sourceRecord(10L, userId, "image", "/shared/image.png");
        AidGenRecord sourceVideo = sourceRecord(11L, userId, "i2v", "/shared/video.mp4");
        sourceVideo.setBaseImageId(sourceImage.getId());
        when(aidGenRecordService.list(any(Wrapper.class))).thenReturn(List.of(sourceImage, sourceVideo));
        AtomicLong clonedId = new AtomicLong(110L);
        when(aidGenRecordService.save(any(AidGenRecord.class))).thenAnswer(invocation -> {
            AidGenRecord saved = invocation.getArgument(0);
            saved.setId(clonedId.getAndIncrement());
            return true;
        });
        when(aidGenRecordService.updateById(any(AidGenRecord.class))).thenReturn(true);
        AidGenRecord clonedFinalImage = sourceRecord(110L, userId, "image", "/shared/image.png");
        clonedFinalImage.setStoryboardId(4818L);
        AidGenRecord clonedFinalVideo = sourceRecord(111L, userId, "i2v", "/shared/video.mp4");
        clonedFinalVideo.setStoryboardId(4818L);
        when(aidGenRecordService.getOne(any(), eq(false)))
                .thenReturn(clonedFinalImage, clonedFinalVideo, null);
        StoryboardCreateRequest request = new StoryboardCreateRequest();
        request.setProjectId(217L);
        request.setEpisodeId(0L);
        request.setSourceStoryboardId(source.getId());
        request.setTitle("分镜脚本11：分镜脚本08_副本");

        StoryboardVO result = service.createStoryboard(request, userId);

        assertEquals(4818L, result.getId());
        assertEquals(source.getStoryScript(), result.getStoryScript());
        assertEquals(source.getDialogueText(), result.getDialogueText());
        assertEquals(110L, result.getFinalImageId());
        assertEquals(111L, result.getFinalVideoId());
        assertNull(result.getFinalAudioId());
        assertEquals("/shared/image.png", result.getFinalImageUrl());
        assertEquals("/shared/video.mp4", result.getFinalVideoUrl());

        ArgumentCaptor<AidGenRecord> records = ArgumentCaptor.forClass(AidGenRecord.class);
        verify(aidGenRecordService, times(2)).save(records.capture());
        AidGenRecord clonedImage = records.getAllValues().get(0);
        AidGenRecord clonedVideo = records.getAllValues().get(1);
        assertEquals(4818L, clonedImage.getStoryboardId());
        assertEquals("/shared/image.png", clonedImage.getFileUrl());
        assertEquals(BigDecimal.ZERO, clonedImage.getCostCredits());
        assertNull(clonedImage.getTaskId());
        assertNull(clonedImage.getBizSeq());
        assertEquals(110L, clonedVideo.getBaseImageId());
        assertEquals("/shared/video.mp4", clonedVideo.getFileUrl());
    }

    private AidStoryboard sourceStoryboard(Long userId)
    {
        AidStoryboard source = new AidStoryboard();
        source.setId(4565L);
        source.setProjectId(217L);
        source.setEpisodeId(0L);
        source.setUserId(userId);
        source.setSortOrder(8L);
        source.setTitle("分镜脚本008");
        source.setStoryScript("镜头组：001\n剧本内容：剧情收尾");
        source.setDialogueText("[科普医师_初始形象]：总结");
        source.setScriptParams("{\"镜号\":\"008\",\"剧本内容\":\"剧情收尾\"}");
        source.setImagePrompt("image prompt");
        source.setVideoPrompt("video prompt");
        source.setFinalImageId(10L);
        source.setFinalVideoId(11L);
        source.setFinalAudioId(12L);
        source.setDelFlag("0");
        return source;
    }

    private AidGenRecord sourceRecord(Long id, Long userId, String genType, String fileUrl)
    {
        AidGenRecord record = new AidGenRecord();
        record.setId(id);
        record.setUserId(userId);
        record.setProjectId(217L);
        record.setEpisodeId(0L);
        record.setStoryboardId(4565L);
        record.setGenType(genType);
        record.setFileUrl(fileUrl);
        record.setTaskId("provider-task");
        record.setStatus(1);
        record.setBizSeq(1000L + id);
        record.setCostCredits(BigDecimal.TEN);
        record.setIsSelected(1);
        record.setDelFlag("0");
        return record;
    }
}
