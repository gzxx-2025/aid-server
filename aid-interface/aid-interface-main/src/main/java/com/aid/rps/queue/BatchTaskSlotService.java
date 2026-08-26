package com.aid.rps.queue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 管理项目剧集维度的批量任务逻辑槽。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchTaskSlotService
{
    public static final String SNAPSHOT_OWNER_KEY = "batchSlotOwner";
    public static final String SNAPSHOT_TYPES_KEY = "logicalBatchSlots";

    private static final String SLOT_KEY_PREFIX = "batch:logical:slot:";
    private static final String ACCEPT_KEY_PREFIX = "batch:logical:accept:";
    private static final String HANDOFF_KEY_PREFIX = "batch:logical:handoff:";
    private static final String TASK_EXECUTING_MESSAGE = "任务执行中，请先停止";
    private static final String DEL_FLAG_NORMAL = "0";
    private static final long PROVISIONAL_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final Duration ACCEPT_LOCK_TTL = Duration.ofSeconds(30);
    private static final long HANDOFF_LEASE_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "SUCCEEDED", "FAILED", "PARTIAL_FAILED", "CANCELLED");
    private static final Set<String> FORM_GENERATE_LOGICAL_TYPES = Set.of(
            BatchTaskLogicalType.FORM_GENERATE_BATCH,
            BatchTaskLogicalType.FORM_GENERATE_CHARACTER_BATCH,
            BatchTaskLogicalType.FORM_GENERATE_SCENE_BATCH,
            BatchTaskLogicalType.FORM_GENERATE_PROP_BATCH);
    private static final Set<String> FORM_IMAGE_LOGICAL_TYPES = Set.of(
            BatchTaskLogicalType.FORM_IMAGE_BATCH,
            BatchTaskLogicalType.FORM_IMAGE_CHARACTER_BATCH,
            BatchTaskLogicalType.FORM_IMAGE_SCENE_BATCH,
            BatchTaskLogicalType.FORM_IMAGE_PROP_BATCH);
    private static final Set<String> SUPPORTED_LOGICAL_TYPES = Set.of(
            BatchTaskLogicalType.ASSET_EXTRACT_CHARACTER,
            BatchTaskLogicalType.ASSET_EXTRACT_SCENE,
            BatchTaskLogicalType.ASSET_EXTRACT_PROP,
            BatchTaskLogicalType.FORM_GENERATE_BATCH,
            BatchTaskLogicalType.FORM_GENERATE_CHARACTER_BATCH,
            BatchTaskLogicalType.FORM_GENERATE_SCENE_BATCH,
            BatchTaskLogicalType.FORM_GENERATE_PROP_BATCH,
            BatchTaskLogicalType.FORM_IMAGE_BATCH,
            BatchTaskLogicalType.FORM_IMAGE_CHARACTER_BATCH,
            BatchTaskLogicalType.FORM_IMAGE_SCENE_BATCH,
            BatchTaskLogicalType.FORM_IMAGE_PROP_BATCH,
            BatchTaskLogicalType.FORM_CARD_IMAGE_BATCH,
            BatchTaskLogicalType.STORYBOARD_SCRIPT_WORKFLOW,
            BatchTaskLogicalType.STORYBOARD_IMAGE_WORKFLOW,
            BatchTaskLogicalType.STORYBOARD_VIDEO_WORKFLOW,
            BatchTaskLogicalType.STORYBOARD_AUDIO_GENERATE,
            BatchTaskLogicalType.STORYBOARD_LIP_SYNC_GENERATE,
            BatchTaskLogicalType.EPISODE_EXPORT);

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local ownCount = tonumber(ARGV[2])
            for i = 1, #KEYS do
                if redis.call('exists', KEYS[i]) == 1 then
                    return 0
                end
            end
            for i = 1, ownCount do
                redis.call('set', KEYS[i], ARGV[1])
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> OPEN_HANDOFF_SCRIPT = new DefaultRedisScript<>("""
            for i = 1, #KEYS - 1 do
                if redis.call('get', KEYS[i]) ~= ARGV[1] then
                    return -1
                end
            end
            if redis.call('exists', KEYS[#KEYS]) == 1 then
                return 2
            end
            redis.call('psetex', KEYS[#KEYS], ARGV[3], ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW_HANDOFF_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final IAidExtractTaskService extractTaskService;
    private final List<BatchTaskExternalActivityProbe> externalActivityProbes;

    /**
     * 原子占用一个或多个任务逻辑槽。
     */
    public BatchTaskSlotReservation acquireTaskSlots(Long projectId, Long episodeId,
                                                     Collection<String> logicalTypes)
    {
        List<String> normalizedTypes = normalizeTypes(logicalTypes);
        return acquire(projectId, episodeId, normalizedTypes,
                () -> hasAnyActiveTask(projectId, episodeId, normalizedTypes));
    }

    /**
     * 占用由其他业务表承载的任务逻辑槽。
     */
    public BatchTaskSlotReservation acquireExternalSlot(Long projectId, Long episodeId,
                                                        String logicalType, BooleanSupplier activeChecker)
    {
        List<String> logicalTypes = normalizeTypes(List.of(logicalType));
        return acquire(projectId, episodeId, logicalTypes,
                () -> hasAnyActiveTask(projectId, episodeId, logicalTypes)
                        || checkActive(activeChecker));
    }

    private BatchTaskSlotReservation acquire(Long projectId, Long episodeId, List<String> logicalTypes,
                                             BooleanSupplier activeChecker)
    {
        requireScope(projectId, episodeId);
        Set<String> conflictTypes = conflictTypes(logicalTypes);
        String acceptOwner = null;
        String acceptKey = null;
        if (CollectionUtil.isNotEmpty(conflictTypes))
        {
            acceptOwner = System.currentTimeMillis() + ":" + IdUtil.fastSimpleUUID();
            acceptKey = ACCEPT_KEY_PREFIX + "{" + projectId + "}:" + episodeId;
            if (!tryAcquireAcceptLock(acceptKey, acceptOwner))
            {
                LinkedHashSet<String> checkedTypes = new LinkedHashSet<>(logicalTypes);
                checkedTypes.addAll(conflictTypes);
                if (checkActive(activeChecker)
                        || hasAnyActiveTask(projectId, episodeId, checkedTypes)
                        || hasFreshReservation(buildKeys(projectId, episodeId, checkedTypes)))
                {
                    rejectConflict(projectId, episodeId, logicalTypes);
                }
                log.warn("批量任务受理门闩等待超时但未发现业务冲突: projectId={}, episodeId={}",
                        projectId, episodeId);
                throw new ServiceException("提交繁忙，请重试");
            }
        }
        try
        {
            if (checkActive(activeChecker) || hasAnyActiveTask(projectId, episodeId, conflictTypes))
            {
                rejectConflict(projectId, episodeId, logicalTypes);
            }
            clearInactiveStaleReservations(projectId, episodeId, logicalTypes, conflictTypes);

            String ownerToken = System.currentTimeMillis() + ":" + IdUtil.fastSimpleUUID();
            List<String> ownKeys = buildKeys(projectId, episodeId, logicalTypes);
            LinkedHashSet<String> allTypes = new LinkedHashSet<>(logicalTypes);
            allTypes.addAll(conflictTypes);
            List<String> checkedKeys = buildKeys(projectId, episodeId, allTypes);
            if (executeAcquire(checkedKeys, ownKeys.size(), ownerToken))
            {
                return new BatchTaskSlotReservation(projectId, episodeId, logicalTypes, ownerToken);
            }

            if (checkActive(activeChecker)
                    || hasAnyActiveTask(projectId, episodeId, conflictTypes)
                    || hasFreshReservation(checkedKeys))
            {
                rejectConflict(projectId, episodeId, logicalTypes);
            }
            clearInactiveStaleReservations(projectId, episodeId, logicalTypes, conflictTypes);
            if (!executeAcquire(checkedKeys, ownKeys.size(), ownerToken))
            {
                rejectConflict(projectId, episodeId, logicalTypes);
            }
            return new BatchTaskSlotReservation(projectId, episodeId, logicalTypes, ownerToken);
        }
        finally
        {
            releaseAcceptLock(acceptKey, acceptOwner);
        }
    }

    /**
     * 校验组合链子任务继续持有父任务逻辑槽。
     */
    public void requireOwned(BatchTaskSlotReservation reservation)
    {
        if (Objects.isNull(reservation) || StrUtil.isBlank(reservation.ownerToken()))
        {
            log.error("批量任务逻辑槽凭证缺失");
            throw new ServiceException("任务状态异常");
        }
        for (String key : buildKeys(reservation.projectId(), reservation.episodeId(), reservation.logicalTypes()))
        {
            String current = getSlotOwner(key);
            if (!Objects.equals(current, reservation.ownerToken()))
            {
                log.info("批量任务逻辑槽持有者已变化: key={}", key);
                throw new ServiceException(TASK_EXECUTING_MESSAGE);
            }
        }
    }

    /** 打开组合链父任务到多个子任务之间的短期原子交接租约。 */
    public String openHandoff(BatchTaskSlotReservation reservation)
    {
        if (Objects.isNull(reservation) || StrUtil.isBlank(reservation.ownerToken()))
        {
            throw new ServiceException("任务状态异常");
        }
        String handoffToken = IdUtil.fastSimpleUUID();
        List<String> keys = new ArrayList<>(buildKeys(
                reservation.projectId(), reservation.episodeId(), reservation.logicalTypes()));
        keys.add(handoffKey(reservation.projectId(), reservation.episodeId(), reservation.ownerToken()));
        try
        {
            Long opened = stringRedisTemplate.execute(OPEN_HANDOFF_SCRIPT, keys,
                    reservation.ownerToken(), handoffToken, String.valueOf(HANDOFF_LEASE_MILLIS));
            if (Objects.equals(2L, opened))
            {
                return null;
            }
            if (!Objects.equals(1L, opened))
            {
                throw new ServiceException(TASK_EXECUTING_MESSAGE);
            }
            return handoffToken;
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            log.error("批量任务交接租约创建失败: projectId={}, episodeId={}",
                    reservation.projectId(), reservation.episodeId(), ex);
            throw new ServiceException("任务状态异常");
        }
    }

    /** 每批子任务创建前续期交接租约。 */
    public void renewHandoff(BatchTaskSlotReservation reservation, String handoffToken)
    {
        if (Objects.isNull(reservation) || StrUtil.isBlank(handoffToken))
        {
            throw new ServiceException("任务状态异常");
        }
        String key = handoffKey(reservation.projectId(), reservation.episodeId(), reservation.ownerToken());
        try
        {
            Long renewed = stringRedisTemplate.execute(RENEW_HANDOFF_SCRIPT, List.of(key),
                    handoffToken, String.valueOf(HANDOFF_LEASE_MILLIS));
            if (!Objects.equals(1L, renewed))
            {
                throw new ServiceException("任务状态异常");
            }
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            log.error("批量任务交接租约续期失败: key={}", key, ex);
            throw new ServiceException("任务状态异常");
        }
    }

    /** 仅由持有者关闭组合链交接租约。 */
    public boolean closeHandoff(BatchTaskSlotReservation reservation, String handoffToken)
    {
        if (Objects.isNull(reservation) || StrUtil.isBlank(handoffToken))
        {
            return false;
        }
        String key = handoffKey(reservation.projectId(), reservation.episodeId(), reservation.ownerToken());
        try
        {
            Long closed = stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(key), handoffToken);
            return Objects.equals(1L, closed);
        }
        catch (Exception ex)
        {
            log.error("批量任务交接租约关闭失败: key={}", key, ex);
            return false;
        }
    }

    /**
     * 把逻辑槽凭证写入任务输入快照。
     */
    public void attachSnapshotMetadata(Map<String, Object> snapshot, BatchTaskSlotReservation reservation)
    {
        if (Objects.isNull(snapshot) || Objects.isNull(reservation))
        {
            return;
        }
        snapshot.put(SNAPSHOT_OWNER_KEY, reservation.ownerToken());
        snapshot.put(SNAPSHOT_TYPES_KEY, reservation.logicalTypes());
    }

    /**
     * 从任务输入快照恢复逻辑槽凭证。
     */
    public BatchTaskSlotReservation reservationFromTask(AidExtractTask task)
    {
        if (Objects.isNull(task) || StrUtil.isBlank(task.getInputSnapshot()))
        {
            return null;
        }
        try
        {
            JSONObject snapshot = JSON.parseObject(task.getInputSnapshot());
            String ownerToken = snapshot.getString(SNAPSHOT_OWNER_KEY);
            JSONArray types = snapshot.getJSONArray(SNAPSHOT_TYPES_KEY);
            if (StrUtil.isBlank(ownerToken) || Objects.isNull(types) || types.isEmpty())
            {
                return null;
            }
            return new BatchTaskSlotReservation(task.getProjectId(), task.getEpisodeId(),
                    normalizeTypes(types.toJavaList(String.class)), ownerToken);
        }
        catch (Exception ex)
        {
            log.warn("批量任务逻辑槽快照解析失败: taskId={}, err={}", task.getId(), ex.getMessage());
            return null;
        }
    }

    /**
     * 仅在同一持有者已无活跃任务时释放逻辑槽。
     */
    public void releaseForTask(AidExtractTask task)
    {
        BatchTaskSlotReservation reservation = reservationFromTask(task);
        if (Objects.isNull(reservation))
        {
            return;
        }
        if (hasActiveHandoff(reservation.projectId(), reservation.episodeId(), reservation.ownerToken()))
        {
            return;
        }
        if (hasActiveOwnedTask(reservation))
        {
            return;
        }
        release(reservation);
    }

    /**
     * 释放逻辑槽。
     */
    public void release(BatchTaskSlotReservation reservation)
    {
        if (Objects.isNull(reservation) || StrUtil.isBlank(reservation.ownerToken()))
        {
            return;
        }
        for (String key : buildKeys(reservation.projectId(), reservation.episodeId(), reservation.logicalTypes()))
        {
            try
            {
                stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(key), reservation.ownerToken());
            }
            catch (Exception ex)
            {
                log.warn("批量任务逻辑槽释放失败: key={}, err={}", key, ex.getMessage());
            }
        }
    }

    private boolean hasActiveOwnedTask(BatchTaskSlotReservation reservation)
    {
        List<AidExtractTask> activeTasks = listActiveTasks(
                reservation.projectId(), reservation.episodeId(), reservation.logicalTypes());
        for (AidExtractTask activeTask : activeTasks)
        {
            BatchTaskSlotReservation activeReservation = reservationFromTask(activeTask);
            if (Objects.nonNull(activeReservation)
                    && Objects.equals(reservation.ownerToken(), activeReservation.ownerToken()))
            {
                return true;
            }
        }
        return false;
    }

    private boolean hasActiveTask(Long projectId, Long episodeId, Collection<String> logicalTypes)
    {
        return CollectionUtil.isNotEmpty(listActiveTasks(projectId, episodeId, logicalTypes));
    }

    private List<AidExtractTask> listActiveTasks(Long projectId, Long episodeId,
                                                Collection<String> logicalTypes)
    {
        Map<Long, AidExtractTask> matched = new java.util.LinkedHashMap<>();
        for (String logicalType : logicalTypes)
        {
            Set<String> taskTypes = physicalTaskTypes(logicalType);
            if (taskTypes.isEmpty())
            {
                continue;
            }
            var query = Wrappers.<AidExtractTask>lambdaQuery()
                    .select(AidExtractTask::getId, AidExtractTask::getProjectId, AidExtractTask::getEpisodeId,
                            AidExtractTask::getTaskType, AidExtractTask::getStatus, AidExtractTask::getInputSnapshot)
                    .eq(AidExtractTask::getProjectId, projectId)
                    .in(AidExtractTask::getTaskType, taskTypes)
                    .and(wrapper -> wrapper.isNull(AidExtractTask::getStatus)
                            .or().notIn(AidExtractTask::getStatus, TERMINAL_STATUSES))
                    .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL);
            if (!Objects.equals(BatchTaskLogicalType.ASSET_EXTRACT_CHARACTER, logicalType))
            {
                query.eq(AidExtractTask::getEpisodeId, episodeId);
            }
            List<AidExtractTask> candidates = extractTaskService.list(query);
            for (AidExtractTask candidate : candidates)
            {
                if (matchesLogicalTypes(candidate, List.of(logicalType)))
                {
                    matched.put(candidate.getId(), candidate);
                }
            }
        }
        return List.copyOf(matched.values());
    }

    private boolean matchesLogicalTypes(AidExtractTask task, Collection<String> logicalTypes)
    {
        if (!Objects.equals("asset_extract", task.getTaskType()))
        {
            if (Objects.equals("form_generate_batch", task.getTaskType()))
            {
                return matchesTypedFormTask(task, logicalTypes, FORM_GENERATE_LOGICAL_TYPES,
                        BatchTaskLogicalType.FORM_GENERATE_BATCH);
            }
            if (Objects.equals("form_image_batch", task.getTaskType()))
            {
                return matchesTypedFormTask(task, logicalTypes, FORM_IMAGE_LOGICAL_TYPES,
                        BatchTaskLogicalType.FORM_IMAGE_BATCH);
            }
            if (Objects.equals("storyboard_image_generate", task.getTaskType()))
            {
                return logicalTypes.contains(BatchTaskLogicalType.STORYBOARD_IMAGE_WORKFLOW)
                        && isBatchStoryboardGeneration(task, BatchTaskLogicalType.STORYBOARD_IMAGE_WORKFLOW);
            }
            if (Objects.equals("storyboard_video_generate", task.getTaskType()))
            {
                return logicalTypes.contains(BatchTaskLogicalType.STORYBOARD_VIDEO_WORKFLOW)
                        && isBatchStoryboardGeneration(task, BatchTaskLogicalType.STORYBOARD_VIDEO_WORKFLOW);
            }
            return logicalTypes.stream().anyMatch(type -> physicalTaskTypes(type).contains(task.getTaskType()));
        }
        Set<String> extractTypes = parseExtractTypes(task.getInputSnapshot());
        if (Objects.isNull(extractTypes))
        {
            return true;
        }
        return logicalTypes.stream().anyMatch(type ->
                (Objects.equals(BatchTaskLogicalType.ASSET_EXTRACT_CHARACTER, type)
                        && extractTypes.contains("character"))
                || (Objects.equals(BatchTaskLogicalType.ASSET_EXTRACT_SCENE, type)
                        && extractTypes.contains("scene"))
                || (Objects.equals(BatchTaskLogicalType.ASSET_EXTRACT_PROP, type)
                        && extractTypes.contains("prop")));
    }

    private boolean matchesTypedFormTask(AidExtractTask task, Collection<String> requestedTypes,
                                         Set<String> familyTypes, String legacyType)
    {
        Set<String> persistedTypes = parsePersistedFormLogicalTypes(task.getInputSnapshot(), familyTypes);
        if (Objects.isNull(persistedTypes) || persistedTypes.contains(legacyType))
        {
            // 无法区分类型的旧任务以及旧通用槽任务继续阻断三类，避免升级时同类任务重入。
            return requestedTypes.stream().anyMatch(familyTypes::contains);
        }
        return requestedTypes.stream()
                .filter(type -> !Objects.equals(legacyType, type))
                .anyMatch(persistedTypes::contains);
    }

    private Set<String> parsePersistedFormLogicalTypes(String inputSnapshot, Set<String> familyTypes)
    {
        if (StrUtil.isBlank(inputSnapshot))
        {
            return null;
        }
        try
        {
            JSONArray persisted = JSON.parseObject(inputSnapshot).getJSONArray(SNAPSHOT_TYPES_KEY);
            if (Objects.isNull(persisted) || persisted.isEmpty())
            {
                return null;
            }
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (String type : persisted.toJavaList(String.class))
            {
                if (!familyTypes.contains(type))
                {
                    return null;
                }
                result.add(type);
            }
            return result.isEmpty() ? null : result;
        }
        catch (Exception ex)
        {
            log.warn("形态批量任务快照解析失败: err={}", ex.getMessage());
            return null;
        }
    }

    /** 判断业务父任务是否属于本功能管理的逻辑批量任务。 */
    public boolean isManagedBatchTask(AidExtractTask task)
    {
        if (Objects.isNull(task))
        {
            return false;
        }
        return SUPPORTED_LOGICAL_TYPES.stream()
                .anyMatch(type -> matchesLogicalTypes(task, List.of(type)));
    }

    private boolean isBatchStoryboardGeneration(AidExtractTask task, String logicalType)
    {
        if (StrUtil.isBlank(task.getInputSnapshot()))
        {
            return false;
        }
        try
        {
            JSONObject snapshot = JSON.parseObject(task.getInputSnapshot());
            JSONArray persistedTypes = snapshot.getJSONArray(SNAPSHOT_TYPES_KEY);
            if (Objects.nonNull(persistedTypes) && !persistedTypes.isEmpty())
            {
                return persistedTypes.toJavaList(String.class).contains(logicalType);
            }
            return hasMultipleStoryboardIds(snapshot);
        }
        catch (Exception ex)
        {
            log.warn("分镜批量任务快照解析失败: taskId={}, err={}", task.getId(), ex.getMessage());
            return true;
        }
    }

    private boolean hasMultipleStoryboardIds(JSONObject snapshot)
    {
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>();
        collectStoryboardIds(snapshot.getJSONArray("storyboardIds"), distinctIds);
        collectStoryboardIds(snapshot.getJSONArray("shots"), distinctIds);
        collectStoryboardIds(snapshot.getJSONArray("allShots"), distinctIds);
        return distinctIds.size() > 1;
    }

    private void collectStoryboardIds(JSONArray values, Set<Long> target)
    {
        if (Objects.isNull(values))
        {
            return;
        }
        for (Object raw : values)
        {
            if (raw instanceof Number number)
            {
                target.add(number.longValue());
                continue;
            }
            JSONObject item = raw instanceof JSONObject object ? object : JSON.parseObject(JSON.toJSONString(raw));
            Long storyboardId = item.getLong("storyboardId");
            if (Objects.nonNull(storyboardId))
            {
                target.add(storyboardId);
            }
        }
    }

    private Set<String> parseExtractTypes(String inputSnapshot)
    {
        String snapshot = StrUtil.trim(inputSnapshot);
        if (StrUtil.isBlank(snapshot))
        {
            return null;
        }
        try
        {
            JSONObject object = JSON.parseObject(snapshot);
            JSONArray types = object.getJSONArray("extractTypes");
            if (Objects.isNull(types) || types.isEmpty())
            {
                return null;
            }
            Set<String> result = new LinkedHashSet<>();
            for (Object type : types)
            {
                String normalized = StrUtil.trim(String.valueOf(type));
                if (!Set.of("character", "scene", "prop").contains(normalized))
                {
                    return null;
                }
                result.add(normalized);
            }
            return result;
        }
        catch (Exception ignored)
        {
            Set<String> result = new LinkedHashSet<>();
            for (String part : snapshot.split(","))
            {
                String normalized = StrUtil.trim(part);
                if (!Set.of("character", "scene", "prop").contains(normalized))
                {
                    return null;
                }
                result.add(normalized);
            }
            return result.isEmpty() ? null : result;
        }
    }

    private Set<String> conflictTypes(Collection<String> logicalTypes)
    {
        Set<String> conflicts = new LinkedHashSet<>();
        for (String logicalType : logicalTypes)
        {
            addLegacyFormConflicts(logicalType, BatchTaskLogicalType.FORM_GENERATE_BATCH,
                    FORM_GENERATE_LOGICAL_TYPES, conflicts);
            addLegacyFormConflicts(logicalType, BatchTaskLogicalType.FORM_IMAGE_BATCH,
                    FORM_IMAGE_LOGICAL_TYPES, conflicts);
            if (Objects.equals(BatchTaskLogicalType.STORYBOARD_SCRIPT_WORKFLOW, logicalType))
            {
                conflicts.add(BatchTaskLogicalType.STORYBOARD_IMAGE_WORKFLOW);
                conflicts.add(BatchTaskLogicalType.STORYBOARD_VIDEO_WORKFLOW);
                conflicts.add(BatchTaskLogicalType.STORYBOARD_AUDIO_GENERATE);
                conflicts.add(BatchTaskLogicalType.STORYBOARD_LIP_SYNC_GENERATE);
                conflicts.add(BatchTaskLogicalType.EPISODE_EXPORT);
            }
            else if (Objects.equals(BatchTaskLogicalType.STORYBOARD_IMAGE_WORKFLOW, logicalType))
            {
                conflicts.add(BatchTaskLogicalType.STORYBOARD_SCRIPT_WORKFLOW);
                conflicts.add(BatchTaskLogicalType.STORYBOARD_VIDEO_WORKFLOW);
                conflicts.add(BatchTaskLogicalType.STORYBOARD_AUDIO_GENERATE);
                conflicts.add(BatchTaskLogicalType.STORYBOARD_LIP_SYNC_GENERATE);
                conflicts.add(BatchTaskLogicalType.EPISODE_EXPORT);
            }
            else if (Objects.equals(BatchTaskLogicalType.STORYBOARD_VIDEO_WORKFLOW, logicalType))
            {
                conflicts.add(BatchTaskLogicalType.STORYBOARD_SCRIPT_WORKFLOW);
                conflicts.add(BatchTaskLogicalType.STORYBOARD_IMAGE_WORKFLOW);
                conflicts.add(BatchTaskLogicalType.STORYBOARD_AUDIO_GENERATE);
                conflicts.add(BatchTaskLogicalType.STORYBOARD_LIP_SYNC_GENERATE);
                conflicts.add(BatchTaskLogicalType.EPISODE_EXPORT);
            }
            else if (Objects.equals(BatchTaskLogicalType.STORYBOARD_AUDIO_GENERATE, logicalType)
                    || Objects.equals(BatchTaskLogicalType.STORYBOARD_LIP_SYNC_GENERATE, logicalType)
                    || Objects.equals(BatchTaskLogicalType.EPISODE_EXPORT, logicalType))
            {
                conflicts.add(BatchTaskLogicalType.STORYBOARD_SCRIPT_WORKFLOW);
                conflicts.add(BatchTaskLogicalType.STORYBOARD_IMAGE_WORKFLOW);
                conflicts.add(BatchTaskLogicalType.STORYBOARD_VIDEO_WORKFLOW);
            }
        }
        conflicts.removeAll(logicalTypes);
        return conflicts;
    }

    private void addLegacyFormConflicts(String logicalType, String legacyType,
                                        Set<String> familyTypes, Set<String> conflicts)
    {
        if (Objects.equals(legacyType, logicalType))
        {
            familyTypes.stream()
                    .filter(type -> !Objects.equals(legacyType, type))
                    .forEach(conflicts::add);
        }
        else if (familyTypes.contains(logicalType))
        {
            conflicts.add(legacyType);
        }
    }

    private boolean hasAnyActiveTask(Long projectId, Long episodeId, Collection<String> logicalTypes)
    {
        for (String logicalType : logicalTypes)
        {
            if (isLogicalTypeActive(projectId, episodeId, logicalType))
            {
                return true;
            }
        }
        return false;
    }

    private boolean isLogicalTypeActive(Long projectId, Long episodeId, String logicalType)
    {
        try
        {
            if (hasActiveTask(projectId, episodeId, List.of(logicalType)))
            {
                return true;
            }
            for (BatchTaskExternalActivityProbe probe : externalActivityProbes)
            {
                if (probe.supports(logicalType)
                        && probe.hasActiveTask(projectId, episodeId, logicalType))
                {
                    return true;
                }
            }
            return false;
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            log.error("批量任务持久化判活失败: projectId={}, episodeId={}, logicalType={}",
                    projectId, episodeId, logicalType, ex);
            throw new ServiceException("任务状态异常");
        }
    }

    private Set<String> physicalTaskTypes(String logicalType)
    {
        return switch (logicalType)
        {
            case BatchTaskLogicalType.ASSET_EXTRACT_CHARACTER,
                 BatchTaskLogicalType.ASSET_EXTRACT_SCENE,
                 BatchTaskLogicalType.ASSET_EXTRACT_PROP -> Set.of("asset_extract");
            case BatchTaskLogicalType.FORM_GENERATE_BATCH,
                 BatchTaskLogicalType.FORM_GENERATE_CHARACTER_BATCH,
                 BatchTaskLogicalType.FORM_GENERATE_SCENE_BATCH,
                 BatchTaskLogicalType.FORM_GENERATE_PROP_BATCH -> Set.of("form_generate_batch");
            case BatchTaskLogicalType.FORM_IMAGE_BATCH,
                 BatchTaskLogicalType.FORM_IMAGE_CHARACTER_BATCH,
                 BatchTaskLogicalType.FORM_IMAGE_SCENE_BATCH,
                 BatchTaskLogicalType.FORM_IMAGE_PROP_BATCH -> Set.of("form_image_batch");
            case BatchTaskLogicalType.FORM_CARD_IMAGE_BATCH -> Set.of("form_card_image_batch");
            case BatchTaskLogicalType.STORYBOARD_SCRIPT_WORKFLOW -> Set.of("storyboard_script_batch");
            case BatchTaskLogicalType.STORYBOARD_IMAGE_WORKFLOW -> Set.of(
                    "storyboard_image_prompt_batch", "storyboard_image_generate");
            case BatchTaskLogicalType.STORYBOARD_VIDEO_WORKFLOW -> Set.of(
                    "storyboard_video_prompt_batch", "storyboard_video_generate");
            case BatchTaskLogicalType.STORYBOARD_AUDIO_GENERATE -> Set.of("storyboard_audio_generate");
            case BatchTaskLogicalType.STORYBOARD_LIP_SYNC_GENERATE -> Set.of("storyboard_lip_sync_generate");
            default -> Set.of();
        };
    }

    private List<String> normalizeTypes(Collection<String> logicalTypes)
    {
        if (CollectionUtil.isEmpty(logicalTypes))
        {
            throw new IllegalArgumentException("logicalTypes must not be empty");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String logicalType : logicalTypes)
        {
            String normalized = StrUtil.trim(logicalType);
            if (!SUPPORTED_LOGICAL_TYPES.contains(normalized))
            {
                throw new IllegalArgumentException("unsupported logical type: " + normalized);
            }
            unique.add(normalized);
        }
        return List.copyOf(unique);
    }

    private void requireScope(Long projectId, Long episodeId)
    {
        if (Objects.isNull(projectId) || Objects.isNull(episodeId) || episodeId < 0)
        {
            log.info("批量任务逻辑槽范围无效: projectId={}, episodeId={}", projectId, episodeId);
            throw new ServiceException("任务范围无效");
        }
    }

    private List<String> buildKeys(Long projectId, Long episodeId, Collection<String> logicalTypes)
    {
        List<String> keys = new ArrayList<>();
        for (String logicalType : logicalTypes)
        {
            keys.add(SLOT_KEY_PREFIX + "{" + projectId + "}:" + slotEpisodeId(episodeId, logicalType)
                    + ":" + logicalType);
        }
        return keys;
    }

    private Long slotEpisodeId(Long episodeId, String logicalType)
    {
        return Objects.equals(BatchTaskLogicalType.ASSET_EXTRACT_CHARACTER, logicalType) ? 0L : episodeId;
    }

    private boolean executeAcquire(List<String> keys, int ownKeyCount, String ownerToken)
    {
        try
        {
            Long result = stringRedisTemplate.execute(
                    ACQUIRE_SCRIPT, keys, ownerToken, String.valueOf(ownKeyCount));
            return Objects.equals(1L, result);
        }
        catch (Exception ex)
        {
            log.error("批量任务逻辑槽占用失败", ex);
            throw new ServiceException("任务状态异常");
        }
    }

    private boolean hasFreshReservation(List<String> keys)
    {
        long now = System.currentTimeMillis();
        for (String key : keys)
        {
            String owner = getSlotOwner(key);
            if (StrUtil.isBlank(owner))
            {
                continue;
            }
            int separator = owner.indexOf(':');
            if (separator <= 0)
            {
                return true;
            }
            try
            {
                if (now - Long.parseLong(owner.substring(0, separator)) <= PROVISIONAL_GRACE_MILLIS)
                {
                    return true;
                }
            }
            catch (NumberFormatException ignored)
            {
                return true;
            }
        }
        return false;
    }

    private void clearInactiveStaleReservations(Long projectId, Long episodeId,
                                                Collection<String> ownTypes,
                                                Collection<String> conflictTypes)
    {
        LinkedHashSet<String> types = new LinkedHashSet<>(ownTypes);
        types.addAll(conflictTypes);
        for (String logicalType : types)
        {
            String key = buildKeys(projectId, episodeId, List.of(logicalType)).get(0);
            String owner = getSlotOwner(key);
            if (StrUtil.isBlank(owner))
            {
                continue;
            }
            if (isFreshOwner(owner)
                    || hasActiveHandoff(projectId, episodeId, owner)
                    || isLogicalTypeActive(projectId, episodeId, logicalType))
            {
                rejectConflict(projectId, episodeId, ownTypes);
            }
            try
            {
                stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(key), owner);
            }
            catch (Exception ex)
            {
                log.error("批量任务僵尸槽清理失败: key={}", key, ex);
                throw new ServiceException("任务状态异常");
            }
        }
    }

    private boolean isFreshOwner(String owner)
    {
        int separator = owner.indexOf(':');
        if (separator <= 0)
        {
            return true;
        }
        try
        {
            return System.currentTimeMillis() - Long.parseLong(owner.substring(0, separator))
                    <= PROVISIONAL_GRACE_MILLIS;
        }
        catch (NumberFormatException ignored)
        {
            return true;
        }
    }

    private String handoffKey(Long projectId, Long episodeId, String ownerToken)
    {
        return HANDOFF_KEY_PREFIX + "{" + projectId + "}:" + episodeId + ":" + ownerToken;
    }

    /** Redis 读取异常时保守视为交接仍活跃，禁止错误释放逻辑槽。 */
    private boolean hasActiveHandoff(Long projectId, Long episodeId, String ownerToken)
    {
        if (Objects.isNull(projectId) || Objects.isNull(episodeId) || StrUtil.isBlank(ownerToken))
        {
            return false;
        }
        String key = handoffKey(projectId, episodeId, ownerToken);
        try
        {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        }
        catch (Exception ex)
        {
            log.error("批量任务交接租约读取失败，保守保留逻辑槽: key={}", key, ex);
            return true;
        }
    }

    private boolean checkActive(BooleanSupplier activeChecker)
    {
        try
        {
            return Objects.nonNull(activeChecker) && activeChecker.getAsBoolean();
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            log.error("批量任务持久化判活失败", ex);
            throw new ServiceException("任务状态异常");
        }
    }

    private String getSlotOwner(String key)
    {
        try
        {
            return stringRedisTemplate.opsForValue().get(key);
        }
        catch (Exception ex)
        {
            log.error("批量任务逻辑槽读取失败: key={}", key, ex);
            throw new ServiceException("任务状态异常");
        }
    }

    private boolean tryAcquireAcceptLock(String key, String owner)
    {
        final int maxAttempts = 60;
        for (int attempt = 0; attempt < maxAttempts; attempt++)
        {
            try
            {
                if (Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                        .setIfAbsent(key, owner, ACCEPT_LOCK_TTL)))
                {
                    return true;
                }
                if (attempt < maxAttempts - 1)
                {
                    Thread.sleep(50L);
                }
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                throw new ServiceException("任务状态异常");
            }
            catch (Exception ex)
            {
                log.error("批量任务受理锁占用失败: key={}", key, ex);
                throw new ServiceException("任务状态异常");
            }
        }
        return false;
    }

    private void releaseAcceptLock(String key, String owner)
    {
        if (StrUtil.isBlank(key) || StrUtil.isBlank(owner))
        {
            return;
        }
        try
        {
            stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(key), owner);
        }
        catch (Exception ex)
        {
            log.warn("批量任务受理锁释放失败: key={}, err={}", key, ex.getMessage());
        }
    }

    private void rejectConflict(Long projectId, Long episodeId, Collection<String> logicalTypes)
    {
        log.info("批量任务逻辑槽冲突: projectId={}, episodeId={}, logicalTypes={}",
                projectId, episodeId, logicalTypes);
        throw new ServiceException(TASK_EXECUTING_MESSAGE);
    }
}
