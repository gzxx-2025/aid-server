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
import com.aid.storyboard.service.IStoryboardImageGenerationService;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜批量出图「非阻塞事件驱动扇入」监听器。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class StoryboardImageGenEventListener
{
    /** 业务任务类型：与 StoryboardImageGenerationServiceImpl.BIZ_TASK_TYPE 一致。 */
    private static final String BIZ_TASK_TYPE_IMAGE_GEN = "storyboard_image_generate";

    @Resource
    private AidMediaTaskMapper aidMediaTaskMapper;

    @Resource
    private IStoryboardImageGenerationService storyboardImageGenerationService;

    /** 终态事件：仅处理失败（FAILED）；成功等 OSS 持久化事件携 ossUrl 再收口。 */
    @EventListener
    @Order(210)
    public void onMediaTaskCompleted(MediaTaskCompletedEvent event)
    {
        if (Objects.isNull(event) || Objects.isNull(event.getTaskId()))
        {
            return;
        }
        AidMediaTask mt = aidMediaTaskMapper.selectById(event.getTaskId());
        if (!isImageGenBizTask(mt))
        {
            return;
        }
        if (MediaTaskStatus.FAILED.name().equals(mt.getStatus()))
        {
            storyboardImageGenerationService.onMediaImageTaskTerminal(mt.getId(), false, null);
            return;
        }
        // 成功但 ossUrl 已就绪也可直接收口（兼容 DIRECT 已持久化场景）；否则等 OSS 事件
        if (MediaTaskStatus.SUCCEEDED.name().equals(mt.getStatus()) && StrUtil.isNotBlank(mt.getOssUrl()))
        {
            storyboardImageGenerationService.onMediaImageTaskTerminal(mt.getId(), true, mt.getOssUrl());
        }
    }

    /** OSS 持久化完成事件：成功收口（携 ossUrl）。 */
    @EventListener
    @Order(210)
    public void onMediaTaskOssPersisted(MediaTaskOssPersistedEvent event)
    {
        if (Objects.isNull(event) || Objects.isNull(event.getTaskId()))
        {
            return;
        }
        AidMediaTask mt = aidMediaTaskMapper.selectById(event.getTaskId());
        if (!isImageGenBizTask(mt))
        {
            return;
        }
        if (StrUtil.isBlank(mt.getOssUrl()))
        {
            log.warn("分镜出图 ossPersisted 事件 ossUrl 仍为空, mediaTaskId={}", mt.getId());
            return;
        }
        storyboardImageGenerationService.onMediaImageTaskTerminal(mt.getId(), true, mt.getOssUrl());
    }

    /** 是否为分镜批量出图业务的媒体任务（IMAGE + storyboard_image_generate + 关联 bizTaskId）。 */
    private boolean isImageGenBizTask(AidMediaTask mt)
    {
        return Objects.nonNull(mt)
                && Objects.equals(MediaType.IMAGE.name(), mt.getMediaType())
                && BIZ_TASK_TYPE_IMAGE_GEN.equals(mt.getBizTaskType())
                && Objects.nonNull(mt.getBizTaskId());
    }
}
