package com.aid.rps.service.impl;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.common.exception.ServiceException;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.service.IStoryboardImagePromptService;
import com.aid.rps.service.IStoryboardScriptService;
import com.aid.rps.service.IStoryboardVideoPromptService;
import com.aid.rps.service.ITaskResumeService;
import com.aid.storyboard.dto.StoryboardImageGenerateVO;
import com.aid.storyboard.dto.StoryboardVideoGenerateVO;
import com.aid.storyboard.service.IStoryboardImageGenerationService;
import com.aid.storyboard.service.IStoryboardVideoGenerationService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一续生分发实现：按 {@code aid_extract_task.task_type} 路由到各类型既有续生实现。
 * 本类只做「加载任务 → 校验归属 → 按类型分发」，不重复各类型的续生业务逻辑；
 * 各下游 Service 内部仍各自做状态 / 窗口 / 缺失补跑等强校验（双重校验，防御式）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class TaskResumeServiceImpl implements ITaskResumeService
{
    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_QUEUED = "QUEUED";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";

    /** 可续生的任务类型常量（与各 Service / AssetExtractServiceImpl 完全一致） */
    private static final String TASK_TYPE_ASSET_EXTRACT = "asset_extract";
    private static final String TASK_TYPE_STORYBOARD_SCRIPT_BATCH = "storyboard_script_batch";
    private static final String TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH = "storyboard_image_prompt_batch";
    private static final String TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH = "storyboard_video_prompt_batch";
    private static final String TASK_TYPE_STORYBOARD_VIDEO_GENERATE = "storyboard_video_generate";
    private static final String TASK_TYPE_STORYBOARD_IMAGE_GENERATE = "storyboard_image_generate";
    private static final String TASK_TYPE_FORM_GENERATE_BATCH = "form_generate_batch";
    private static final String TASK_TYPE_FORM_IMAGE_BATCH = "form_image_batch";
    private static final String TASK_TYPE_FORM_CARD_IMAGE_BATCH = "form_card_image_batch";

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private IAssetExtractService assetExtractService;

    @Autowired
    private IStoryboardScriptService storyboardScriptService;

    @Autowired
    private IStoryboardImagePromptService storyboardImagePromptService;

    @Autowired
    private IStoryboardVideoPromptService storyboardVideoPromptService;

    @Autowired
    private IStoryboardVideoGenerationService storyboardVideoGenerationService;

    @Autowired
    private IStoryboardImageGenerationService storyboardImageGenerationService;

    @Override
    public Object resume(Long taskId, Long userId)
    {
        if (Objects.isNull(taskId) || taskId <= 0)
        {
            log.error("统一续生入参无效: taskId={}", taskId);
            throw new ServiceException("参数错误");
        }
        if (Objects.isNull(userId) || userId <= 0)
        {
            log.error("统一续生登录态缺失: userId={}", userId);
            throw new ServiceException("请先登录");
        }

        AidExtractTask task = extractTaskService.getById(taskId);
        if (Objects.isNull(task) || !DEL_FLAG_NORMAL.equals(task.getDelFlag()))
        {
            log.error("统一续生任务不存在: taskId={}", taskId);
            throw new ServiceException("任务不存在");
        }
        if (!Objects.equals(userId, task.getUserId()))
        {
            log.error("统一续生归属校验失败: taskId={}, owner={}, req={}", taskId, task.getUserId(), userId);
            throw new ServiceException("无权访问");
        }

        String taskType = task.getTaskType();
        if (StrUtil.isBlank(taskType))
        {
            log.error("统一续生任务类型为空: taskId={}", taskId);
            throw new ServiceException("类型不支持");
        }
        if (isSupportedTaskType(taskType) && isActiveStatus(task.getStatus()))
        {
            log.info("统一续生活跃任务直接返回: taskId={}, taskType={}, status={}",
                    taskId, taskType, task.getStatus());
            return buildActiveResponse(task);
        }
        log.info("统一续生分发: taskId={}, taskType={}, userId={}", taskId, taskType, userId);
        switch (taskType)
        {
            case TASK_TYPE_ASSET_EXTRACT:
                return assetExtractService.resumeExtract(taskId, userId);
            case TASK_TYPE_STORYBOARD_SCRIPT_BATCH:
                return storyboardScriptService.resumeStoryboardScript(taskId, userId);
            case TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH:
                return storyboardImagePromptService.resumeImagePrompt(taskId, userId);
            case TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH:
                return storyboardVideoPromptService.resumeVideoPrompt(taskId, userId);
            case TASK_TYPE_STORYBOARD_VIDEO_GENERATE:
                return storyboardVideoGenerationService.resumeVideo(taskId, userId);
            case TASK_TYPE_STORYBOARD_IMAGE_GENERATE:
                return storyboardImageGenerationService.resumeImage(taskId, userId);
            case TASK_TYPE_FORM_GENERATE_BATCH:
            case TASK_TYPE_FORM_IMAGE_BATCH:
            case TASK_TYPE_FORM_CARD_IMAGE_BATCH:
                return assetExtractService.resumeFormBatchTask(taskId, userId);
            default:
                log.error("统一续生不支持的任务类型: taskId={}, taskType={}", taskId, taskType);
                throw new ServiceException("类型不支持");
        }
    }

    private boolean isActiveStatus(String status)
    {
        return TASK_STATUS_PENDING.equals(status)
                || TASK_STATUS_QUEUED.equals(status)
                || TASK_STATUS_PROCESSING.equals(status);
    }

    private Object buildActiveResponse(AidExtractTask task)
    {
        if (TASK_TYPE_STORYBOARD_VIDEO_GENERATE.equals(task.getTaskType()))
        {
            StoryboardVideoGenerateVO vo = new StoryboardVideoGenerateVO();
            vo.setTaskId(task.getId());
            vo.setStatus(task.getStatus());
            vo.setModelName(task.getModelCode());
            vo.setTotalSubtasks(task.getTotalCount());
            return vo;
        }
        if (TASK_TYPE_STORYBOARD_IMAGE_GENERATE.equals(task.getTaskType()))
        {
            StoryboardImageGenerateVO vo = new StoryboardImageGenerateVO();
            vo.setTaskId(task.getId());
            vo.setStatus(task.getStatus());
            vo.setModelName(task.getModelCode());
            vo.setTotalSubtasks(task.getTotalCount());
            return vo;
        }
        return AssetExtractTaskVO.builder()
                .taskId(task.getId())
                .status(task.getStatus())
                .totalCount(task.getTotalCount())
                .totalShots(task.getTotalCount())
                .build();
    }

    private boolean isSupportedTaskType(String taskType)
    {
        return TASK_TYPE_ASSET_EXTRACT.equals(taskType)
                || TASK_TYPE_STORYBOARD_SCRIPT_BATCH.equals(taskType)
                || TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH.equals(taskType)
                || TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH.equals(taskType)
                || TASK_TYPE_STORYBOARD_VIDEO_GENERATE.equals(taskType)
                || TASK_TYPE_STORYBOARD_IMAGE_GENERATE.equals(taskType)
                || TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType)
                || TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType)
                || TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType);
    }
}
