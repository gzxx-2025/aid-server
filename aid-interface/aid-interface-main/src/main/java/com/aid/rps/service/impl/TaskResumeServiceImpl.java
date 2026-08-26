package com.aid.rps.service.impl;

import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.common.exception.ServiceException;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.queue.BatchTaskLogicalType;
import com.aid.rps.queue.BatchTaskSlotReservation;
import com.aid.rps.queue.BatchTaskSlotService;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.service.IStoryboardImagePromptService;
import com.aid.rps.service.IStoryboardScriptService;
import com.aid.rps.service.IStoryboardVideoPromptService;
import com.aid.rps.service.ITaskResumeService;
import com.aid.project.service.IUserProjectBusinessService;
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
    private static final String TASK_STATUS_FINALIZING = "FINALIZING";
    private static final String TASK_STATUS_RECOVERING = "RECOVERING";

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
    private static final String ASSET_TYPE_CHARACTER = "character";
    private static final String ASSET_TYPE_SCENE = "scene";
    private static final String ASSET_TYPE_PROP = "prop";

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

    @Autowired
    private IUserProjectBusinessService userProjectBusinessService;

    @Autowired
    private BatchTaskSlotService batchTaskSlotService;

    @Autowired
    private IAidRolePropSceneService rpsService;

    @Autowired
    private IAidRolePropSceneFormService rpsFormService;

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
        BatchTaskSlotReservation resumeReservation = reserveLogicalSlotForResume(task);
        log.info("统一续生分发: taskId={}, taskType={}, userId={}", taskId, taskType, userId);
        try
        {
            return dispatchResume(taskType, taskId, userId);
        }
        catch (RuntimeException ex)
        {
            releaseFailedResumeReservation(taskId, resumeReservation);
            throw ex;
        }
    }

    private Object dispatchResume(String taskType, Long taskId, Long userId)
    {
        return switch (taskType)
        {
            case TASK_TYPE_ASSET_EXTRACT -> assetExtractService.resumeExtract(taskId, userId);
            case TASK_TYPE_STORYBOARD_SCRIPT_BATCH -> storyboardScriptService.resumeStoryboardScript(taskId, userId);
            case TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH -> storyboardImagePromptService.resumeImagePrompt(taskId, userId);
            case TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH -> storyboardVideoPromptService.resumeVideoPrompt(taskId, userId);
            case TASK_TYPE_STORYBOARD_VIDEO_GENERATE -> storyboardVideoGenerationService.resumeVideo(taskId, userId);
            case TASK_TYPE_STORYBOARD_IMAGE_GENERATE -> storyboardImageGenerationService.resumeImage(taskId, userId);
            case TASK_TYPE_FORM_GENERATE_BATCH, TASK_TYPE_FORM_IMAGE_BATCH,
                    TASK_TYPE_FORM_CARD_IMAGE_BATCH -> assetExtractService.resumeFormBatchTask(taskId, userId);
            default -> throw new ServiceException("类型不支持");
        };
    }

    /**
     * 续生会把原终态任务重新变为活跃，必须在任何子项锁、计费重置和状态 CAS 之前重新占槽。
     * 新 owner 与原业务快照一次条件更新，避免“停止→新建→续生旧任务”并发。
     */
    private BatchTaskSlotReservation reserveLogicalSlotForResume(AidExtractTask task)
    {
        JSONObject snapshot = parseResumeSnapshot(task);
        List<String> logicalTypes = resolveResumeLogicalTypes(task, snapshot);
        if (logicalTypes.isEmpty())
        {
            // 原本就是单条 image/video 的任务不扩大为剧集级互斥。
            return null;
        }
        BatchTaskSlotReservation reservation = batchTaskSlotService.acquireTaskSlots(
                task.getProjectId(), task.getEpisodeId(), logicalTypes);
        try
        {
            Map<String, Object> snapshotMap = snapshot;
            batchTaskSlotService.attachSnapshotMetadata(snapshotMap, reservation);
            String updatedSnapshot = JSON.toJSONString(snapshotMap);
            var update = Wrappers.<AidExtractTask>lambdaUpdate()
                    .eq(AidExtractTask::getId, task.getId())
                    .eq(AidExtractTask::getUserId, task.getUserId())
                    .eq(AidExtractTask::getStatus, task.getStatus());
            if (task.getInputSnapshot() == null)
            {
                update.isNull(AidExtractTask::getInputSnapshot);
            }
            else
            {
                update.eq(AidExtractTask::getInputSnapshot, task.getInputSnapshot());
            }
            update.set(AidExtractTask::getInputSnapshot, updatedSnapshot);
            String resumedResultData = clearChainCancelledMarker(task.getResultData());
            if (!Objects.equals(task.getResultData(), resumedResultData))
            {
                update.set(AidExtractTask::getResultData, resumedResultData);
            }
            if (!extractTaskService.update(update))
            {
                throw new ServiceException("任务状态已变化");
            }
            task.setInputSnapshot(updatedSnapshot);
            return reservation;
        }
        catch (RuntimeException ex)
        {
            batchTaskSlotService.release(reservation);
            throw ex;
        }
    }

    private String clearChainCancelledMarker(String resultData)
    {
        if (StrUtil.isBlank(resultData))
        {
            return resultData;
        }
        try
        {
            JSONObject result = JSON.parseObject(resultData);
            if (result == null || !Boolean.TRUE.equals(result.getBoolean("chainCancelled")))
            {
                return resultData;
            }
            result.remove("chainCancelled");
            return JSON.toJSONString(result);
        }
        catch (Exception ex)
        {
            throw new ServiceException("任务状态异常");
        }
    }

    private void releaseFailedResumeReservation(Long taskId, BatchTaskSlotReservation reservation)
    {
        if (reservation == null)
        {
            return;
        }
        AidExtractTask current = extractTaskService.getById(taskId);
        if (current == null || !isActiveStatus(current.getStatus()))
        {
            batchTaskSlotService.release(reservation);
        }
    }

    private JSONObject parseResumeSnapshot(AidExtractTask task)
    {
        try
        {
            JSONObject snapshot = JSON.parseObject(task.getInputSnapshot());
            if (snapshot != null)
            {
                return snapshot;
            }
        }
        catch (Exception ex)
        {
            if (!TASK_TYPE_ASSET_EXTRACT.equals(task.getTaskType()))
            {
                throw new ServiceException("任务状态异常");
            }
        }
        if (!TASK_TYPE_ASSET_EXTRACT.equals(task.getTaskType()))
        {
            throw new ServiceException("任务状态异常");
        }
        String rawSnapshot = StrUtil.trim(task.getInputSnapshot());
        if (StrUtil.isBlank(rawSnapshot))
        {
            throw new ServiceException("任务状态异常");
        }
        LinkedHashSet<String> extractTypes = new LinkedHashSet<>();
        for (String part : rawSnapshot.split(","))
        {
            String type = StrUtil.trim(part);
            if (!Set.of("character", "scene", "prop").contains(type))
            {
                throw new ServiceException("任务状态异常");
            }
            extractTypes.add(type);
        }
        if (extractTypes.isEmpty())
        {
            throw new ServiceException("任务状态异常");
        }
        JSONObject compatible = new JSONObject();
        compatible.put("extractTypes", new ArrayList<>(extractTypes));
        return compatible;
    }

    private List<String> resolveResumeLogicalTypes(AidExtractTask task, JSONObject snapshot)
    {
        JSONArray persistedTypes = snapshot.getJSONArray("logicalBatchSlots");
        if (persistedTypes != null && !persistedTypes.isEmpty())
        {
            List<String> types = persistedTypes.toJavaList(String.class);
            if (TASK_TYPE_FORM_GENERATE_BATCH.equals(task.getTaskType())
                    && types.contains(BatchTaskLogicalType.FORM_GENERATE_BATCH))
            {
                return resolveLegacyFormLogicalType(task, snapshot, false);
            }
            if (TASK_TYPE_FORM_IMAGE_BATCH.equals(task.getTaskType())
                    && types.contains(BatchTaskLogicalType.FORM_IMAGE_BATCH))
            {
                return resolveLegacyFormLogicalType(task, snapshot, true);
            }
            return types;
        }
        return switch (task.getTaskType())
        {
            case TASK_TYPE_ASSET_EXTRACT -> resolveAssetLogicalTypes(snapshot);
            case TASK_TYPE_STORYBOARD_SCRIPT_BATCH -> List.of(BatchTaskLogicalType.STORYBOARD_SCRIPT_WORKFLOW);
            case TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH -> List.of(BatchTaskLogicalType.STORYBOARD_IMAGE_WORKFLOW);
            case TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH -> List.of(BatchTaskLogicalType.STORYBOARD_VIDEO_WORKFLOW);
            case TASK_TYPE_FORM_GENERATE_BATCH -> resolveLegacyFormLogicalType(task, snapshot, false);
            case TASK_TYPE_FORM_IMAGE_BATCH -> resolveLegacyFormLogicalType(task, snapshot, true);
            case TASK_TYPE_FORM_CARD_IMAGE_BATCH -> List.of(BatchTaskLogicalType.FORM_CARD_IMAGE_BATCH);
            case TASK_TYPE_STORYBOARD_IMAGE_GENERATE -> hasMultipleStoryboardIds(snapshot)
                    ? List.of(BatchTaskLogicalType.STORYBOARD_IMAGE_WORKFLOW) : List.of();
            case TASK_TYPE_STORYBOARD_VIDEO_GENERATE -> hasMultipleStoryboardIds(snapshot)
                    ? List.of(BatchTaskLogicalType.STORYBOARD_VIDEO_WORKFLOW) : List.of();
            default -> List.of();
        };
    }

    private List<String> resolveLegacyFormLogicalType(AidExtractTask task, JSONObject snapshot,
                                                       boolean imageTask)
    {
        String assetType = StrUtil.trim(snapshot.getString("assetType"));
        if (StrUtil.isBlank(assetType))
        {
            assetType = imageTask
                    ? resolveFormImageAssetType(snapshot.getJSONArray("formIds"))
                    : resolveFormGenerateAssetType(snapshot.getJSONArray("assetIds"));
        }
        String logicalType = mapFormLogicalType(assetType, imageTask);
        if (StrUtil.isNotBlank(logicalType))
        {
            return List.of(logicalType);
        }
        log.warn("旧形态任务无法恢复资产类型，继续占用兼容槽: taskId={}, taskType={}",
                task.getId(), task.getTaskType());
        return List.of(imageTask ? BatchTaskLogicalType.FORM_IMAGE_BATCH
                : BatchTaskLogicalType.FORM_GENERATE_BATCH);
    }

    private String resolveFormGenerateAssetType(JSONArray rawIds)
    {
        LinkedHashSet<Long> assetIds = parsePositiveIds(rawIds);
        if (assetIds.isEmpty())
        {
            return null;
        }
        try
        {
            List<AidRolePropScene> assets = rpsService.listByIds(assetIds);
            return resolveSingleAssetType(assets, assetIds.size());
        }
        catch (Exception ex)
        {
            log.warn("旧形态任务资产类型查询失败: err={}", ex.getMessage());
            return null;
        }
    }

    private String resolveFormImageAssetType(JSONArray rawIds)
    {
        LinkedHashSet<Long> formIds = parsePositiveIds(rawIds);
        if (formIds.isEmpty())
        {
            return null;
        }
        try
        {
            List<AidRolePropSceneForm> forms = rpsFormService.listByIds(formIds);
            if (forms.size() != formIds.size())
            {
                return null;
            }
            LinkedHashSet<Long> assetIds = new LinkedHashSet<>();
            for (AidRolePropSceneForm form : forms)
            {
                if (Objects.isNull(form.getAssetId()) || form.getAssetId() <= 0)
                {
                    return null;
                }
                assetIds.add(form.getAssetId());
            }
            List<AidRolePropScene> assets = rpsService.listByIds(assetIds);
            return resolveSingleAssetType(assets, assetIds.size());
        }
        catch (Exception ex)
        {
            log.warn("旧形态图任务资产类型查询失败: err={}", ex.getMessage());
            return null;
        }
    }

    private LinkedHashSet<Long> parsePositiveIds(JSONArray rawIds)
    {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (rawIds == null)
        {
            return ids;
        }
        for (Object raw : rawIds)
        {
            Long id;
            try
            {
                id = raw instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(raw));
            }
            catch (Exception ex)
            {
                return new LinkedHashSet<>();
            }
            if (id <= 0)
            {
                return new LinkedHashSet<>();
            }
            ids.add(id);
        }
        return ids;
    }

    private String resolveSingleAssetType(List<AidRolePropScene> assets, int expectedCount)
    {
        if (assets == null || assets.size() != expectedCount)
        {
            return null;
        }
        String assetType = null;
        for (AidRolePropScene asset : assets)
        {
            String currentType = StrUtil.trim(asset.getAssetType());
            if (!Set.of(ASSET_TYPE_CHARACTER, ASSET_TYPE_SCENE, ASSET_TYPE_PROP).contains(currentType))
            {
                return null;
            }
            if (assetType == null)
            {
                assetType = currentType;
            }
            else if (!Objects.equals(assetType, currentType))
            {
                return null;
            }
        }
        return assetType;
    }

    private String mapFormLogicalType(String assetType, boolean imageTask)
    {
        if (ASSET_TYPE_CHARACTER.equals(assetType))
        {
            return imageTask ? BatchTaskLogicalType.FORM_IMAGE_CHARACTER_BATCH
                    : BatchTaskLogicalType.FORM_GENERATE_CHARACTER_BATCH;
        }
        if (ASSET_TYPE_SCENE.equals(assetType))
        {
            return imageTask ? BatchTaskLogicalType.FORM_IMAGE_SCENE_BATCH
                    : BatchTaskLogicalType.FORM_GENERATE_SCENE_BATCH;
        }
        if (ASSET_TYPE_PROP.equals(assetType))
        {
            return imageTask ? BatchTaskLogicalType.FORM_IMAGE_PROP_BATCH
                    : BatchTaskLogicalType.FORM_GENERATE_PROP_BATCH;
        }
        return null;
    }

    private List<String> resolveAssetLogicalTypes(JSONObject snapshot)
    {
        JSONArray extractTypes = snapshot.getJSONArray("extractTypes");
        if (extractTypes == null || extractTypes.isEmpty())
        {
            // 无法精确恢复旧快照时 fail-closed 占用三类，避免从续生绕过任一资产提取槽。
            return List.of(BatchTaskLogicalType.ASSET_EXTRACT_CHARACTER,
                    BatchTaskLogicalType.ASSET_EXTRACT_SCENE, BatchTaskLogicalType.ASSET_EXTRACT_PROP);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String type : extractTypes.toJavaList(String.class))
        {
            if ("character".equals(type)) result.add(BatchTaskLogicalType.ASSET_EXTRACT_CHARACTER);
            else if ("scene".equals(type)) result.add(BatchTaskLogicalType.ASSET_EXTRACT_SCENE);
            else if ("prop".equals(type)) result.add(BatchTaskLogicalType.ASSET_EXTRACT_PROP);
            else return List.of(BatchTaskLogicalType.ASSET_EXTRACT_CHARACTER,
                        BatchTaskLogicalType.ASSET_EXTRACT_SCENE, BatchTaskLogicalType.ASSET_EXTRACT_PROP);
        }
        return new ArrayList<>(result);
    }

    private boolean hasMultipleStoryboardIds(JSONObject snapshot)
    {
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>();
        collectStoryboardIds(snapshot.getJSONArray("storyboardIds"), distinctIds);
        collectStoryboardIds(snapshot.getJSONArray("shots"), distinctIds);
        collectStoryboardIds(snapshot.getJSONArray("allShots"), distinctIds);
        return distinctIds.size() > 1;
    }

    private void collectStoryboardIds(JSONArray shots, LinkedHashSet<Long> target)
    {
        if (shots == null)
        {
            return;
        }
        for (Object raw : shots)
        {
            if (raw instanceof Number number)
            {
                target.add(number.longValue());
            }
            else if (raw instanceof JSONObject shot)
            {
                Long storyboardId = shot.getLong("storyboardId");
                if (storyboardId != null)
                {
                    target.add(storyboardId);
                }
                else
                {
                    throw new ServiceException("任务数据异常");
                }
            }
            else if (raw instanceof Map<?, ?> map)
            {
                Object value = map.get("storyboardId");
                if (value instanceof Number number)
                {
                    target.add(number.longValue());
                }
                else
                {
                    throw new ServiceException("任务数据异常");
                }
            }
            else
            {
                throw new ServiceException("任务数据异常");
            }
        }
    }

    @Override
    public BillingQuoteVO quoteResume(Long taskId, Long userId)
    {
        AidExtractTask task = loadOwnedTask(taskId, userId);
        if (isActiveStatus(task.getStatus()))
        {
            throw new ServiceException("状态不支持");
        }
        if (Objects.nonNull(task.getProjectId())
                && userProjectBusinessService.selectUserProjectById(task.getProjectId(), userId) == null)
        {
            throw new ServiceException("项目不存在");
        }
        String taskType = task.getTaskType();
        if (StrUtil.isBlank(taskType))
        {
            throw new ServiceException("类型不支持");
        }
        return switch (taskType)
        {
            case TASK_TYPE_ASSET_EXTRACT -> assetExtractService.quoteResumeExtract(taskId, userId);
            case TASK_TYPE_STORYBOARD_SCRIPT_BATCH ->
                    storyboardScriptService.quoteResumeStoryboardScript(taskId, userId);
            case TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH ->
                    storyboardImagePromptService.quoteResumeImagePrompt(taskId, userId);
            case TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH ->
                    storyboardVideoPromptService.quoteResumeVideoPrompt(taskId, userId);
            case TASK_TYPE_STORYBOARD_VIDEO_GENERATE ->
                    storyboardVideoGenerationService.quoteResumeVideo(taskId, userId);
            case TASK_TYPE_STORYBOARD_IMAGE_GENERATE ->
                    storyboardImageGenerationService.quoteResumeImage(taskId, userId);
            case TASK_TYPE_FORM_GENERATE_BATCH, TASK_TYPE_FORM_IMAGE_BATCH,
                    TASK_TYPE_FORM_CARD_IMAGE_BATCH ->
                    assetExtractService.quoteResumeFormBatchTask(taskId, userId);
            default -> throw new ServiceException("类型不支持");
        };
    }

    private AidExtractTask loadOwnedTask(Long taskId, Long userId)
    {
        if (Objects.isNull(taskId) || taskId <= 0 || Objects.isNull(userId) || userId <= 0)
        {
            throw new ServiceException("参数错误");
        }
        AidExtractTask task = extractTaskService.getById(taskId);
        if (Objects.isNull(task) || !DEL_FLAG_NORMAL.equals(task.getDelFlag()))
        {
            throw new ServiceException("任务不存在");
        }
        if (!Objects.equals(userId, task.getUserId()))
        {
            throw new ServiceException("无权访问");
        }
        return task;
    }

    private boolean isActiveStatus(String status)
    {
        return StrUtil.isBlank(status)
                || TASK_STATUS_PENDING.equals(status)
                || TASK_STATUS_QUEUED.equals(status)
                || TASK_STATUS_PROCESSING.equals(status)
                || TASK_STATUS_FINALIZING.equals(status)
                || TASK_STATUS_RECOVERING.equals(status);
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
