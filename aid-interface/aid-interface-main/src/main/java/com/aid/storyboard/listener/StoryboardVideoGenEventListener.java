package com.aid.storyboard.listener;

import java.util.Objects;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import com.aid.media.event.MediaTaskCompletedEvent;
import com.aid.media.event.MediaTaskOssPersistedEvent;
import com.aid.storyboard.service.IStoryboardVideoGenerationService;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜批量出片「非阻塞事件驱动扇入」监听器（与出图监听同范式）。
 * 单条视频由 media 调度中心轮询/回调驱动完成；本监听器在媒体任务终态事件时把成功/失败回流给
 * {@link IStoryboardVideoGenerationService#onMediaVideoTaskTerminal}。成功收口走 OSS 持久化事件（携 ossUrl），
 * 失败走终态事件。仅处理 {@code media_type=VIDEO 且 biz_task_type=storyboard_video_generate}。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class StoryboardVideoGenEventListener
{
    /** 业务任务类型：与 StoryboardVideoGenerationServiceImpl.BIZ_TASK_TYPE 一致。 */
    private static final String BIZ_TASK_TYPE_VIDEO_GEN = "storyboard_video_generate";

    @Resource
    private AidMediaTaskMapper aidMediaTaskMapper;

    @Resource
    private IStoryboardVideoGenerationService storyboardVideoGenerationService;

    @EventListener
    @Order(210)
    public void onMediaTaskCompleted(MediaTaskCompletedEvent event)
    {
        if (Objects.isNull(event) || Objects.isNull(event.getTaskId()))
        {
            return;
        }
        AidMediaTask mt = aidMediaTaskMapper.selectById(event.getTaskId());
        if (!isVideoGenBizTask(mt))
        {
            return;
        }
        if (MediaTaskStatus.FAILED.name().equals(mt.getStatus()))
        {
            storyboardVideoGenerationService.onMediaVideoTaskTerminal(mt.getId(), false, null);
            return;
        }
        if (MediaTaskStatus.SUCCEEDED.name().equals(mt.getStatus()) && StrUtil.isNotBlank(mt.getOssUrl()))
        {
            storyboardVideoGenerationService.onMediaVideoTaskTerminal(mt.getId(), true, mt.getOssUrl());
        }
    }

    @EventListener
    @Order(210)
    public void onMediaTaskOssPersisted(MediaTaskOssPersistedEvent event)
    {
        if (Objects.isNull(event) || Objects.isNull(event.getTaskId()))
        {
            return;
        }
        AidMediaTask mt = aidMediaTaskMapper.selectById(event.getTaskId());
        if (!isVideoGenBizTask(mt))
        {
            return;
        }
        if (StrUtil.isBlank(mt.getOssUrl()))
        {
            log.warn("分镜出片 ossPersisted 事件 ossUrl 仍为空, mediaTaskId={}", mt.getId());
            return;
        }
        storyboardVideoGenerationService.onMediaVideoTaskTerminal(mt.getId(), true, mt.getOssUrl());
    }

    private boolean isVideoGenBizTask(AidMediaTask mt)
    {
        return Objects.nonNull(mt)
                && Objects.equals(MediaType.VIDEO.name(), mt.getMediaType())
                && BIZ_TASK_TYPE_VIDEO_GEN.equals(mt.getBizTaskType())
                && Objects.nonNull(mt.getBizTaskId());
    }
}
