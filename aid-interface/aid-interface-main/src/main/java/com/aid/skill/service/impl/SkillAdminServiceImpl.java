package com.aid.skill.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.common.exception.ServiceException;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillRelation;
import com.aid.skill.domain.AidSkillRun;
import com.aid.skill.domain.AidSkillRunStep;
import com.aid.skill.domain.AidSkillRunTaskLink;
import com.aid.skill.dto.SkillAdminRequests;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillRelationMapper;
import com.aid.skill.mapper.AidSkillRunMapper;
import com.aid.skill.mapper.AidSkillRunStepMapper;
import com.aid.skill.mapper.AidSkillRunTaskLinkMapper;
import com.aid.skill.service.ISkillAdminService;
import com.aid.skill.service.SkillRuntimeCapabilities;
import com.aid.skill.service.SkillModelService;
import com.aid.skill.vo.SkillAdminVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Administration is independent from the removed legacy chat implementation. */
@Service
@RequiredArgsConstructor
public class SkillAdminServiceImpl implements ISkillAdminService {
    private static final String NORMAL = "0";
    private static final String DELETED = "1";
    private static final String DISABLED = "1";

    private final AidSkillMapper skillMapper;
    private final AidSkillRelationMapper relationMapper;
    private final AidSkillRunMapper runMapper;
    private final AidSkillRunStepMapper stepMapper;
    private final AidSkillRunTaskLinkMapper taskLinkMapper;
    private final AidMediaTaskMapper mediaTaskMapper;
    private final SkillModelService skillModelService;

    @Override
    public SkillAdminVO.PageResult<SkillAdminVO.SkillSummary> pageSkills(
            SkillAdminRequests.PageRequest request) {
        SkillAdminRequests.PageRequest source = Objects.requireNonNullElseGet(
                request, SkillAdminRequests.PageRequest::new);
        Page<AidSkill> page = skillMapper.selectPage(
                new Page<>(positive(source.getPageNum(), 1), bounded(source.getPageSize(), 20, 100)),
                new LambdaQueryWrapper<AidSkill>()
                        .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getName,
                                AidSkill::getDescription, AidSkill::getCapabilityDescription,
                                AidSkill::getIconUrl, AidSkill::getOwnerType,
                                AidSkill::getVisibility, AidSkill::getInvocationScope,
                                AidSkill::getCurrentVersionId, AidSkill::getStatus, AidSkill::getDelFlag,
                                AidSkill::getModelCode, AidSkill::getReasoningPolicy, AidSkill::getUpdateTime)
                        .ne(AidSkill::getSkillCode, SkillRuntimeCapabilities.RETIRED_SCREENPLAY_CHAT)
                        .and(StrUtil.isNotBlank(source.getKeyword()), query -> query
                                .like(AidSkill::getName, source.getKeyword())
                                .or().like(AidSkill::getSkillCode, source.getKeyword()))
                        .eq(StrUtil.isNotBlank(source.getStatus()), AidSkill::getStatus, source.getStatus())
                        .orderByDesc(AidSkill::getId));
        return new SkillAdminVO.PageResult<>(page.getTotal(), page.getRecords().stream()
                .map(this::toSkillSummary).toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateIdentity(SkillAdminRequests.IdentitySaveRequest request,
                               Long operatorId, String operatorName) {
        AidSkill skill = requireActiveSkillForUpdate(request.getId());
        AidSkill update = new AidSkill();
        update.setId(skill.getId());
        update.setName(StrUtil.trim(request.getName()));
        update.setDescription(StrUtil.trim(request.getDescription()));
        update.setCapabilityDescription(StrUtil.trim(request.getCapabilityDescription()));
        update.setIconUrl(StrUtil.trim(request.getIconUrl()));
        String nextStatus = normalizeStatus(request.getStatus());
        if (NORMAL.equals(skill.getStatus()) && DISABLED.equals(nextStatus)) {
            ensureSkillNotInUse(skill);
        }
        update.setStatus(nextStatus);
        update.setUpdateBy(operator(operatorName, operatorId));
        update.setUpdateTime(new Date());
        ensureChanged(skillMapper.update(update, Wrappers.<AidSkill>lambdaUpdate()
                .eq(AidSkill::getId, skill.getId()).eq(AidSkill::getDelFlag, NORMAL)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(SkillAdminRequests.StatusRequest request,
                             Long operatorId, String operatorName) {
        AidSkill skill = requireActiveSkillForUpdate(request.getId());
        String nextStatus = normalizeStatus(request.getStatus());
        if (NORMAL.equals(skill.getStatus()) && DISABLED.equals(nextStatus)) {
            ensureSkillNotInUse(skill);
        }
        ensureChanged(skillMapper.update(null, new LambdaUpdateWrapper<AidSkill>()
                .eq(AidSkill::getId, skill.getId()).eq(AidSkill::getDelFlag, NORMAL)
                .set(AidSkill::getStatus, nextStatus)
                .set(AidSkill::getUpdateBy, operator(operatorName, operatorId))
                .set(AidSkill::getUpdateTime, new Date())));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSkill(Long id, Long operatorId, String operatorName) {
        AidSkill skill = requireActiveSkillForUpdate(id);
        ensureSkillNotInUse(skill);
        ensureChanged(skillMapper.update(null, new LambdaUpdateWrapper<AidSkill>()
                .eq(AidSkill::getId, skill.getId()).eq(AidSkill::getDelFlag, NORMAL)
                .set(AidSkill::getStatus, DISABLED).set(AidSkill::getDelFlag, DELETED)
                .set(AidSkill::getUpdateBy, operator(operatorName, operatorId))
                .set(AidSkill::getUpdateTime, new Date())));
    }

    @Override
    public void restoreSkill(Long id, Long operatorId, String operatorName) {
        AidSkill skill = requireSkill(id);
        if (!DELETED.equals(skill.getDelFlag())) {
            throw new ServiceException("Skill is not deleted");
        }
        ensureChanged(skillMapper.update(null, new LambdaUpdateWrapper<AidSkill>()
                .eq(AidSkill::getId, skill.getId()).eq(AidSkill::getDelFlag, DELETED)
                .set(AidSkill::getStatus, DISABLED).set(AidSkill::getDelFlag, NORMAL)
                .set(AidSkill::getUpdateBy, operator(operatorName, operatorId))
                .set(AidSkill::getUpdateTime, new Date())));
    }

    @Override
    public List<SkillAdminVO.TextModelOption> listTextModelOptions() {
        return skillModelService.adminOptions();
    }

    @Override
    public SkillAdminVO.PageResult<SkillAdminVO.RunSummary> pageRuns(
            SkillAdminRequests.RunPageRequest request) {
        SkillAdminRequests.RunPageRequest source = Objects.requireNonNullElseGet(
                request, SkillAdminRequests.RunPageRequest::new);
        Page<AidSkillRun> page = runMapper.selectPage(
                new Page<>(positive(source.getPageNum(), 1), bounded(source.getPageSize(), 20, 100)),
                Wrappers.<AidSkillRun>lambdaQuery()
                        .select(AidSkillRun::getId, AidSkillRun::getUserId, AidSkillRun::getSkillId,
                                AidSkillRun::getSkillVersionId, AidSkillRun::getProjectId,
                                AidSkillRun::getEpisodeId, AidSkillRun::getSkillConfigHash,
                                AidSkillRun::getModelCode, AidSkillRun::getInvokeSource,
                                AidSkillRun::getClientRequestId, AidSkillRun::getGeneration,
                                AidSkillRun::getStatus, AidSkillRun::getStage, AidSkillRun::getActionMode,
                                AidSkillRun::getQualityMode, AidSkillRun::getStartedAt,
                                AidSkillRun::getFinishedAt)
                        .eq(source.getSkillId() != null, AidSkillRun::getSkillId, source.getSkillId())
                        .eq(source.getUserId() != null, AidSkillRun::getUserId, source.getUserId())
                        .eq(StrUtil.isNotBlank(source.getStatus()), AidSkillRun::getStatus, source.getStatus())
                        .isNotNull(AidSkillRun::getSkillVersionId)
                        .eq(AidSkillRun::getDelFlag, NORMAL)
                        .orderByDesc(AidSkillRun::getId));
        return new SkillAdminVO.PageResult<>(page.getTotal(), page.getRecords().stream()
                .map(this::toRunSummary).toList());
    }

    @Override
    public SkillAdminVO.RunItem getRun(Long runId) {
        AidSkillRun run = runMapper.selectOne(Wrappers.<AidSkillRun>lambdaQuery()
                .eq(AidSkillRun::getId, runId).isNotNull(AidSkillRun::getSkillVersionId)
                .eq(AidSkillRun::getDelFlag, NORMAL).last("limit 1"));
        if (run == null) {
            throw new ServiceException("Runtime run not found");
        }
        SkillAdminVO.RunItem item = toRunItem(run);
        item.setTasks(loadRunTasks(run.getId()));
        return item;
    }

    private List<SkillAdminVO.RunTaskItem> loadRunTasks(Long runId) {
        List<AidSkillRunStep> steps = stepMapper.selectList(Wrappers.<AidSkillRunStep>lambdaQuery()
                .eq(AidSkillRunStep::getRunId, runId).eq(AidSkillRunStep::getDelFlag, NORMAL)
                .orderByAsc(AidSkillRunStep::getStepSeq).orderByAsc(AidSkillRunStep::getId));
        if (steps.isEmpty()) {
            return List.of();
        }
        List<AidSkillRunTaskLink> links = taskLinkMapper.selectList(
                Wrappers.<AidSkillRunTaskLink>lambdaQuery().eq(AidSkillRunTaskLink::getRunId, runId)
                        .eq(AidSkillRunTaskLink::getDelFlag, NORMAL));
        Map<Long, AidSkillRunTaskLink> linkByStep = links.stream().collect(Collectors.toMap(
                AidSkillRunTaskLink::getStepId, Function.identity(), (left, right) -> left));
        List<Long> taskIds = links.stream().map(AidSkillRunTaskLink::getMediaTaskId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, AidMediaTask> tasks = taskIds.isEmpty() ? Collections.emptyMap()
                : mediaTaskMapper.selectList(Wrappers.<AidMediaTask>lambdaQuery()
                        .select(AidMediaTask::getId, AidMediaTask::getStatus,
                                AidMediaTask::getBillingStatus, AidMediaTask::getActualCost)
                        .in(AidMediaTask::getId, taskIds)).stream()
                        .collect(Collectors.toMap(AidMediaTask::getId, Function.identity()));
        return steps.stream().map(step -> toRunTask(step, linkByStep.get(step.getId()), tasks)).toList();
    }

    private SkillAdminVO.RunTaskItem toRunTask(AidSkillRunStep step, AidSkillRunTaskLink link,
                                                Map<Long, AidMediaTask> tasks) {
        SkillAdminVO.RunTaskItem item = new SkillAdminVO.RunTaskItem();
        item.setStepId(step.getId());
        item.setStepSeq(step.getStepSeq());
        item.setStepKey(step.getStepKey());
        item.setStepExecutionId(step.getStepExecutionId());
        item.setSkillId(step.getSkillId());
        item.setSkillVersionId(step.getSkillVersionId());
        item.setActionMode(step.getActionMode());
        item.setWorkflowAttempt(step.getWorkflowAttempt());
        item.setOrchestrationStatus(step.getOrchestrationStatus());
        if (link != null) {
            item.setMediaTaskId(link.getMediaTaskId());
            AidMediaTask task = tasks.get(link.getMediaTaskId());
            if (task != null) {
                item.setMediaStatus(task.getStatus());
                item.setBillingStatus(task.getBillingStatus());
                item.setActualCost(task.getActualCost() == null ? null : task.getActualCost().toPlainString());
            }
        }
        return item;
    }

    private AidSkill requireSkill(Long id) {
        if (id == null || id <= 0) {
            throw new ServiceException("Invalid Skill id");
        }
        AidSkill skill = skillMapper.selectById(id);
        if (skill == null || SkillRuntimeCapabilities.RETIRED_SCREENPLAY_CHAT.equals(skill.getSkillCode())) {
            throw new ServiceException("Skill not found");
        }
        return skill;
    }

    private AidSkill requireActiveSkillForUpdate(Long id) {
        if (id == null || id <= 0) {
            throw new ServiceException("Invalid Skill id");
        }
        AidSkill skill = skillMapper.selectOne(Wrappers.<AidSkill>lambdaQuery()
                .eq(AidSkill::getId, id).last("FOR UPDATE"));
        if (skill == null || SkillRuntimeCapabilities.RETIRED_SCREENPLAY_CHAT.equals(skill.getSkillCode())) {
            throw new ServiceException("Skill not found");
        }
        if (!NORMAL.equals(skill.getDelFlag())) {
            throw new ServiceException("Skill is deleted");
        }
        return skill;
    }

    private SkillAdminVO.SkillSummary toSkillSummary(AidSkill skill) {
        SkillAdminVO.SkillSummary item = new SkillAdminVO.SkillSummary();
        item.setId(skill.getId());
        item.setSkillCode(skill.getSkillCode());
        item.setName(SkillRuntimeCapabilities.displayName(skill.getSkillCode(), skill.getName()));
        item.setDescription(SkillRuntimeCapabilities.displayDescription(
                skill.getSkillCode(), skill.getDescription()));
        item.setCapabilityDescription(skill.getCapabilityDescription());
        item.setIconUrl(skill.getIconUrl());
        item.setOwnerType(skill.getOwnerType());
        item.setVisibility(skill.getVisibility());
        item.setInvocationScope(skill.getInvocationScope());
        item.setCurrentVersionId(skill.getCurrentVersionId());
        item.setStatus(skill.getStatus());
        item.setDelFlag(skill.getDelFlag());
        item.setModelCode(skill.getModelCode());
        item.setReasoningPolicy(skill.getReasoningPolicy());
        item.setUpdateTime(skill.getUpdateTime());
        return item;
    }

    private SkillAdminVO.RunSummary toRunSummary(AidSkillRun run) {
        SkillAdminVO.RunSummary item = new SkillAdminVO.RunSummary();
        copyRunSummary(run, item);
        return item;
    }

    private SkillAdminVO.RunItem toRunItem(AidSkillRun run) {
        SkillAdminVO.RunItem item = new SkillAdminVO.RunItem();
        copyRunSummary(run, item);
        item.setClientRequestDigest(run.getClientRequestDigest());
        item.setExecutionSnapshotDigest(run.getExecutionSnapshotDigest());
        item.setResolvedConfigDigest(run.getResolvedConfigDigest());
        item.setRootRunId(run.getRootRunId());
        item.setParentRunId(run.getParentRunId());
        item.setInputJson(run.getInputJson());
        item.setOutputJson(run.getOutputJson());
        item.setErrorMessage(run.getErrorMessage());
        return item;
    }

    private void copyRunSummary(AidSkillRun run, SkillAdminVO.RunSummary item) {
        item.setId(run.getId());
        item.setUserId(run.getUserId());
        item.setSkillId(run.getSkillId());
        item.setSkillVersionId(run.getSkillVersionId());
        item.setProjectId(run.getProjectId());
        item.setEpisodeId(run.getEpisodeId());
        item.setSkillConfigHash(run.getSkillConfigHash());
        item.setModelCode(run.getModelCode());
        item.setInvokeSource(run.getInvokeSource());
        item.setClientRequestId(run.getClientRequestId());
        item.setGeneration(run.getGeneration());
        item.setStatus(run.getStatus());
        item.setStage(run.getStage());
        item.setActionMode(run.getActionMode());
        item.setQualityMode(run.getQualityMode());
        item.setStartedAt(run.getStartedAt());
        item.setFinishedAt(run.getFinishedAt());
        if (run.getStartedAt() != null && run.getFinishedAt() != null) {
            item.setDurationMillis(Math.max(0L,
                    run.getFinishedAt().getTime() - run.getStartedAt().getTime()));
        }
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private void ensureSkillNotInUse(AidSkill skill) {
        List<Long> parentVersionIds = relationMapper.selectList(
                        Wrappers.<AidSkillRelation>lambdaQuery()
                                .select(AidSkillRelation::getParentVersionId)
                                .eq(AidSkillRelation::getChildSkillId, skill.getId())
                                .eq(AidSkillRelation::getDelFlag, NORMAL))
                .stream().map(AidSkillRelation::getParentVersionId)
                .filter(Objects::nonNull).distinct().toList();
        if (parentVersionIds.isEmpty()) {
            return;
        }
        Long activeEntrypoints = skillMapper.selectCount(Wrappers.<AidSkill>lambdaQuery()
                .in(AidSkill::getCurrentVersionId, parentVersionIds)
                .eq(AidSkill::getInvocationScope, "ENTRYPOINT")
                .eq(AidSkill::getStatus, NORMAL)
                .eq(AidSkill::getDelFlag, NORMAL));
        Long activeRuns = runMapper.selectCount(Wrappers.<AidSkillRun>lambdaQuery()
                .in(AidSkillRun::getSkillVersionId, parentVersionIds)
                .notIn(AidSkillRun::getStatus, "SUCCEEDED", "FAILED", "CANCELED")
                .eq(AidSkillRun::getDelFlag, NORMAL));
        if ((activeEntrypoints != null && activeEntrypoints > 0)
                || (activeRuns != null && activeRuns > 0)) {
            throw new ServiceException("Skill is referenced by an active Runtime package or Run");
        }
    }

    private static int bounded(Integer value, int fallback, int max) {
        return Math.min(positive(value, fallback), max);
    }

    private static String normalizeStatus(String status) {
        if (!NORMAL.equals(status) && !DISABLED.equals(status)) {
            throw new ServiceException("Invalid Skill status");
        }
        return status;
    }

    private static String operator(String name, Long id) {
        return StrUtil.blankToDefault(StrUtil.trim(name), id == null ? "system" : String.valueOf(id));
    }

    private static void ensureChanged(int changed) {
        if (changed != 1) {
            throw new ServiceException("Skill changed concurrently");
        }
    }
}
