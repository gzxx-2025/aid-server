package com.aid.project.snapshot.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.aid.aid.domain.AidAudioAsset;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidProjectGenConfig;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.domain.AidScenePlot;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidProjectGenConfigMapper;
import com.aid.aid.service.IAidAudioAssetService;
import com.aid.aid.service.IAidAudioRecordService;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidComicScriptService;
import com.aid.aid.service.IAidEpisodeEditorService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.aid.service.IAidMediaTaskService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.aid.service.IAidScenePlotService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.core.domain.BaseEntity;
import com.aid.common.event.ProjectAuditPassedEvent;
import com.aid.common.event.ProjectCascadeDeletingEvent;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.project.snapshot.domain.AidProjectCopyRecord;
import com.aid.project.snapshot.domain.AidProjectPublishSnapshot;
import com.aid.project.snapshot.domain.AidProjectPublishSnapshotMedia;
import com.aid.project.snapshot.dto.AdminProjectCopyLabelRequest;
import com.aid.project.snapshot.dto.AdminProjectSnapshotRebuildRequest;
import com.aid.project.snapshot.dto.ProjectSnapshotCopyRequest;
import com.aid.project.snapshot.mapper.AidProjectCopyRecordMapper;
import com.aid.project.snapshot.mapper.AidProjectPublishSnapshotMapper;
import com.aid.project.snapshot.mapper.AidProjectPublishSnapshotMediaMapper;
import com.aid.project.snapshot.model.ProjectCaseAccessReason;
import com.aid.project.snapshot.model.ProjectCaseAccessSubject;
import com.aid.project.snapshot.model.ProjectSnapshotDocument;
import com.aid.project.snapshot.service.IProjectCaseAccessService;
import com.aid.project.snapshot.service.IProjectSnapshotService;
import com.aid.project.snapshot.vo.ProjectCaseAccessSummary;
import com.aid.project.snapshot.vo.ProjectSnapshotCopyResultVO;
import com.aid.project.snapshot.vo.ProjectSnapshotPreviewVO;
import com.aid.rps.voice.service.IRoleVoiceBindingBusinessService;
import com.aid.rps.voice.vo.RoleVoiceBindingVO;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 发布流程快照、只读预览和电影工程复制。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectSnapshotServiceImpl implements IProjectSnapshotService
{
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_NODE_COUNT = 10_000;
    private static final int MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024;
    private static final int SNAPSHOT_RETENTION_COUNT = 20;
    private static final int SNAPSHOT_RETENTION_DAYS = 180;
    private static final int PROJECT_STATUS_DRAFT = 0;
    private static final int PROJECT_STATUS_AUDIT_PASSED = 4;
    private static final int PREVIEW_STEP = 7;
    private static final String NORMAL = "0";
    private static final String YES = "1";
    private static final String MOVIE = "movie";
    private static final long MOVIE_EPISODE_ID = 0L;
    private static final int GEN_SUCCESS = 1;
    private static final String AUDIO_SUCCEEDED = "SUCCEEDED";
    private static final String IMAGE_COMPLETED = "completed";
    private static final String DEFAULT_COPY_LABEL = "复制";
    private static final String COPY_LOCK_PREFIX = "aid:project:snapshot:copy:";
    private static final String REBUILD_LOCK_PREFIX = "aid:project:snapshot:rebuild:";

    private final AidProjectPublishSnapshotMapper snapshotMapper;
    private final AidProjectPublishSnapshotMediaMapper snapshotMediaMapper;
    private final AidProjectCopyRecordMapper copyRecordMapper;
    private final IProjectCaseAccessService projectCaseAccessService;
    private final IAidComicProjectService projectService;
    private final IAidComicEpisodeService episodeService;
    private final IAidComicScriptService scriptService;
    private final IAidRolePropSceneService assetService;
    private final IAidRolePropSceneFormService formService;
    private final IAidRolePropSceneFormImageService formImageService;
    private final IAidScenePlotService scenePlotService;
    private final IAidStoryboardService storyboardService;
    private final IAidGenRecordService genRecordService;
    private final IAidAudioRecordService audioRecordService;
    private final IAidMediaTaskService mediaTaskService;
    private final IAidAudioAssetService audioAssetService;
    private final IAidRoleVoiceBindingService voiceBindingService;
    private final IRoleVoiceBindingBusinessService roleVoiceBindingBusinessService;
    private final IAidEpisodeEditorService editorService;
    private final AidProjectGenConfigMapper projectGenConfigMapper;
    private final MediaUrlResolver mediaUrlResolver;
    private final ProjectSnapshotStageAssembler snapshotStageAssembler;
    private final RedissonClient redissonClient;

    /** 与审核事务同步执行；快照失败时审核整体回滚。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void onProjectAuditPassed(ProjectAuditPassedEvent event)
    {
        captureApprovedSnapshot(event.projectId(), event.ownerUserId(), "audit-snapshot");
    }

    /** 项目主记录删除前同步清理复制幂等记录和无活动血缘的快照。 */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void onProjectCascadeDeleting(ProjectCascadeDeletingEvent event)
    {
        deleteProjectSnapshotData(event.projectId());
    }

    /** 项目删除提交后再次收口并发删除场景遗留的无活动血缘快照。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void afterProjectCascadeDeleted(ProjectCascadeDeletingEvent event)
    {
        Set<Long> candidates = new LinkedHashSet<>();
        candidates.add(event.sourceSnapshotId());
        snapshotMapper.selectList(Wrappers.<AidProjectPublishSnapshot>lambdaQuery()
                .select(AidProjectPublishSnapshot::getId)
                .eq(AidProjectPublishSnapshot::getProjectId, event.projectId()))
                .forEach(row -> candidates.add(row.getId()));
        removeUnprotectedSnapshots(candidates, null, event.projectId());
    }

    @Override
    public void captureApprovedSnapshot(Long projectId, Long ownerUserId, String operator)
    {
        AidComicProject project = projectService.getOne(Wrappers.<AidComicProject>lambdaQuery()
                .eq(AidComicProject::getId, projectId)
                .eq(AidComicProject::getUserId, ownerUserId)
                .eq(AidComicProject::getDelFlag, NORMAL));
        if (project == null || !Objects.equals(project.getStatus(), PROJECT_STATUS_AUDIT_PASSED)) {
            log.info("生成发布快照拒绝，项目未过审: projectId={}, ownerUserId={}", projectId, ownerUserId);
            throw new ServiceException("项目未过审");
        }

        ProjectSnapshotDocument document = loadDocument(project);
        int nodeCount = countNodes(document);
        if (nodeCount > MAX_NODE_COUNT) {
            log.info("生成发布快照拒绝，节点过多: projectId={}, nodes={}", projectId, nodeCount);
            throw new ServiceException("流程节点过多");
        }
        String snapshotJson = JSON.toJSONString(document);
        if (snapshotJson.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
            log.info("生成发布快照拒绝，内容过大: projectId={}, chars={}", projectId, snapshotJson.length());
            throw new ServiceException("流程快照过大");
        }
        String contentHash = sha256(snapshotJson);
        AidProjectPublishSnapshot previous = snapshotMapper.selectOne(
                Wrappers.<AidProjectPublishSnapshot>lambdaQuery()
                        .eq(AidProjectPublishSnapshot::getProjectId, projectId)
                        .orderByDesc(AidProjectPublishSnapshot::getRevisionNo)
                        .last("LIMIT 1"));

        AidProjectPublishSnapshot snapshot = new AidProjectPublishSnapshot();
        snapshot.setProjectId(projectId);
        snapshot.setOwnerUserId(ownerUserId);
        snapshot.setRevisionNo(previous == null ? 1 : previous.getRevisionNo() + 1);
        snapshot.setSchemaVersion(SCHEMA_VERSION);
        snapshot.setContentHash(contentHash);
        snapshot.setSnapshotJson(snapshotJson);
        snapshot.setNodeCount(nodeCount);
        snapshot.setCreateTime(DateUtils.getNowDate());
        snapshot.setCreateBy(operator);
        if (snapshotMapper.insert(snapshot) != 1 || snapshot.getId() == null) {
            log.error("发布快照保存失败: projectId={}", projectId);
            throw new ServiceException("快照保存失败");
        }
        saveSnapshotMediaReferences(snapshot.getId(), document);

        boolean switched = projectService.update(Wrappers.<AidComicProject>lambdaUpdate()
                .eq(AidComicProject::getId, projectId)
                .eq(AidComicProject::getUserId, ownerUserId)
                .eq(AidComicProject::getStatus, PROJECT_STATUS_AUDIT_PASSED)
                .set(AidComicProject::getPublishedSnapshotId, snapshot.getId())
                .set(AidComicProject::getUpdateBy, operator)
                .set(AidComicProject::getUpdateTime, DateUtils.getNowDate()));
        if (!switched) {
            log.error("发布快照切换失败: projectId={}, snapshotId={}", projectId, snapshot.getId());
            throw new ServiceException("快照切换失败");
        }
        pruneHistoricalSnapshots(projectId, snapshot.getId());
        log.info("项目发布快照已固化, projectId={}, snapshotId={}, revision={}, nodes={}",
                projectId, snapshot.getId(), snapshot.getRevisionNo(), nodeCount);
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectSnapshotPreviewVO preview(Long projectId)
    {
        AidComicProject project = requirePublicProject(projectId, false, "LOCK IN SHARE MODE");
        AidProjectPublishSnapshot snapshot = requireCurrentSnapshot(project);
        ProjectSnapshotDocument document = parseDocument(snapshot);
        return buildPreview(project, snapshot, document);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectSnapshotCopyResultVO copy(ProjectSnapshotCopyRequest request, Long userId, String operator)
    {
        RLock lock = redissonClient.getLock(COPY_LOCK_PREFIX + userId + ":" + request.getRequestId());
        boolean locked = false;
        boolean unlockAfterCompletion = false;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);
            if (!locked) {
                log.info("复制请求锁等待超时: projectId={}, userId={}, requestId={}",
                        request.getId(), userId, request.getRequestId());
                throw new ServiceException("复制处理中");
            }
            // 事务代理会在方法返回后才提交；锁必须持有到提交/回滚完成，防止第二个请求读不到首个幂等记录。
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status)
                    {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                });
                unlockAfterCompletion = true;
            }
            // 锁后才进行首次一致性读，避免 MySQL REPEATABLE READ 在等待锁前固定旧快照。
            AidProjectCopyRecord existing = findCopyRecord(userId, request.getRequestId());
            if (existing != null) {
                return reuseCopyResult(existing, request.getId());
            }
            AidComicProject source = requirePublicProject(request.getId(), true, "FOR UPDATE");
            AidProjectPublishSnapshot snapshot = requireCurrentSnapshot(source);
            ProjectSnapshotDocument document = parseDocument(snapshot);
            validateMovieDocument(document, source, snapshot);
            Long targetProjectId = copyMovieDocument(document, source, snapshot, request, userId, operator);

            AidProjectCopyRecord record = new AidProjectCopyRecord();
            record.setUserId(userId);
            record.setRequestId(request.getRequestId());
            record.setSourceProjectId(source.getId());
            record.setSourceSnapshotId(snapshot.getId());
            record.setTargetProjectId(targetProjectId);
            record.setCreateTime(DateUtils.getNowDate());
            ensureCopySaved(copyRecordMapper.insert(record) == 1, "copy-record", source.getId());

            boolean counted = projectService.update(Wrappers.<AidComicProject>lambdaUpdate()
                    .eq(AidComicProject::getId, source.getId())
                    .setSql("copy_count = IFNULL(copy_count, 0) + 1"));
            ensureCopySaved(counted, "copy-count", source.getId());
            return ProjectSnapshotCopyResultVO.builder()
                    .projectId(targetProjectId)
                    .sourceProjectId(source.getId())
                    .sourceSnapshotId(snapshot.getId())
                    .copyLabel(DEFAULT_COPY_LABEL)
                    .reused(false)
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("复制项目锁等待被中断: projectId={}, userId={}", request.getId(), userId);
            throw new ServiceException("复制请求已中断");
        } finally {
            if (locked && !unlockAfterCompletion && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void updateCopyLabel(AdminProjectCopyLabelRequest request, String operator)
    {
        AidComicProject project = projectService.getOne(Wrappers.<AidComicProject>lambdaQuery()
                .select(AidComicProject::getId, AidComicProject::getSourceProjectId)
                .eq(AidComicProject::getId, request.getId())
                .eq(AidComicProject::getDelFlag, NORMAL));
        if (project == null) {
            log.info("修改复制标签失败，项目不存在: projectId={}", request.getId());
            throw new ServiceException("项目不存在");
        }
        if (project.getSourceProjectId() == null) {
            log.info("修改复制标签拒绝，项目无复制来源: projectId={}", request.getId());
            throw new ServiceException("原创项目无标签");
        }
        String label = StrUtil.blankToDefault(StrUtil.trim(request.getCopyLabel()), DEFAULT_COPY_LABEL);
        boolean updated = projectService.update(Wrappers.<AidComicProject>lambdaUpdate()
                .eq(AidComicProject::getId, project.getId())
                .set(AidComicProject::getCopyLabel, label)
                .set(AidComicProject::getUpdateBy, operator)
                .set(AidComicProject::getUpdateTime, DateUtils.getNowDate()));
        if (!updated) {
            log.error("修改复制标签失败，更新未生效: projectId={}", project.getId());
            throw new ServiceException("标签修改失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rebuildApprovedSnapshot(AdminProjectSnapshotRebuildRequest request, String operator)
    {
        RLock lock = redissonClient.getLock(REBUILD_LOCK_PREFIX + request.getId());
        boolean locked = false;
        boolean unlockAfterCompletion = false;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);
            if (!locked) {
                throw new ServiceException("快照正在重建");
            }
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status)
                    {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                });
                unlockAfterCompletion = true;
            }
            AidComicProject project = projectService.getOne(Wrappers.<AidComicProject>lambdaQuery()
                    .eq(AidComicProject::getId, request.getId())
                    .eq(AidComicProject::getDelFlag, NORMAL).last("FOR UPDATE"));
            if (project == null) {
                throw new ServiceException("项目不存在");
            }
            if (!Objects.equals(project.getStatus(), PROJECT_STATUS_AUDIT_PASSED)) {
                throw new ServiceException("仅已过审项目可重建快照");
            }
            if (!YES.equals(project.getIsPublic())) {
                throw new ServiceException("仅已发布项目可重建");
            }
            AidProjectPublishSnapshot current = requireCurrentSnapshot(project);
            ProjectSnapshotDocument currentDocument = loadDocument(project);
            ProjectSnapshotDocument approvedDocument = parseDocument(current);
            if (!Objects.equals(approvedContentDigest(approvedDocument),
                    approvedContentDigest(currentDocument))) {
                log.info("后台重建项目发布快照拒绝，已过审内容发生变化: projectId={}, snapshotId={}",
                        project.getId(), current.getId());
                throw new ServiceException("内容已变化请提审");
            }
            if (Objects.equals(rebuildDigest(approvedDocument), rebuildDigest(currentDocument))) {
                log.info("后台重建项目发布快照跳过相同内容, projectId={}, snapshotId={}",
                        project.getId(), current.getId());
                return;
            }
            captureApprovedSnapshot(project.getId(), project.getUserId(), operator);
            log.info("后台重建项目发布快照完成, projectId={}, operator={}, reason={}",
                    project.getId(), operator,
                    StrUtil.trim(request.getReason()).replaceAll("[\\r\\n\\t]+", " "));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("快照重建已中断");
        } finally {
            if (locked && !unlockAfterCompletion && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private ProjectSnapshotDocument loadDocument(AidComicProject project)
    {
        Long projectId = project.getId();
        ProjectSnapshotDocument document = new ProjectSnapshotDocument();
        document.setSchemaVersion(SCHEMA_VERSION);
        document.setProject(project);
        document.setEpisodes(episodeService.list(Wrappers.<AidComicEpisode>lambdaQuery()
                .eq(AidComicEpisode::getProjectId, projectId).eq(AidComicEpisode::getDelFlag, NORMAL)
                .orderByAsc(AidComicEpisode::getEpisodeNo, AidComicEpisode::getId)));
        document.setScripts(scriptService.list(Wrappers.<AidComicScript>lambdaQuery()
                .eq(AidComicScript::getProjectId, projectId).eq(AidComicScript::getDelFlag, NORMAL)
                .orderByAsc(AidComicScript::getEpisodeId, AidComicScript::getId)));
        document.setAssets(assetService.list(Wrappers.<AidRolePropScene>lambdaQuery()
                .eq(AidRolePropScene::getProjectId, projectId).eq(AidRolePropScene::getDelFlag, NORMAL)
                .orderByAsc(AidRolePropScene::getEpisodeId, AidRolePropScene::getId)));
        document.setForms(formService.list(Wrappers.<AidRolePropSceneForm>lambdaQuery()
                .eq(AidRolePropSceneForm::getProjectId, projectId).eq(AidRolePropSceneForm::getDelFlag, NORMAL)
                .orderByAsc(AidRolePropSceneForm::getEpisodeId, AidRolePropSceneForm::getId)));
        document.setFormImages(formImageService.list(Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                .eq(AidRolePropSceneFormImage::getProjectId, projectId)
                .eq(AidRolePropSceneFormImage::getDelFlag, NORMAL)
                .orderByAsc(AidRolePropSceneFormImage::getFormId,
                        AidRolePropSceneFormImage::getSortOrder,
                        AidRolePropSceneFormImage::getCreateTime,
                        AidRolePropSceneFormImage::getId)));
        document.setScenePlots(scenePlotService.list(Wrappers.<AidScenePlot>lambdaQuery()
                .eq(AidScenePlot::getProjectId, projectId).eq(AidScenePlot::getDelFlag, NORMAL)
                .orderByAsc(AidScenePlot::getEpisodeId, AidScenePlot::getId)));
        document.setStoryboards(storyboardService.list(Wrappers.<AidStoryboard>lambdaQuery()
                .eq(AidStoryboard::getProjectId, projectId).eq(AidStoryboard::getDelFlag, NORMAL)
                .orderByAsc(AidStoryboard::getEpisodeId, AidStoryboard::getSortOrder, AidStoryboard::getId)));
        document.setGenerationRecords(genRecordService.list(Wrappers.<AidGenRecord>lambdaQuery()
                .eq(AidGenRecord::getProjectId, projectId).eq(AidGenRecord::getDelFlag, NORMAL)
                .orderByAsc(AidGenRecord::getId)));
        document.setAudioRecords(audioRecordService.list(Wrappers.<AidAudioRecord>lambdaQuery()
                .eq(AidAudioRecord::getProjectId, projectId).eq(AidAudioRecord::getDelFlag, NORMAL)
                .orderByAsc(AidAudioRecord::getId)));
        freezeLipSyncStatuses(document);
        document.setAudioAssets(audioAssetService.list(Wrappers.<AidAudioAsset>lambdaQuery()
                .eq(AidAudioAsset::getProjectId, projectId).eq(AidAudioAsset::getDelFlag, NORMAL)
                .orderByAsc(AidAudioAsset::getId)));
        document.setVoiceBindings(voiceBindingService.list(Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                .eq(AidRoleVoiceBinding::getProjectId, projectId).eq(AidRoleVoiceBinding::getDelFlag, NORMAL)
                .orderByAsc(AidRoleVoiceBinding::getId)));
        List<Long> characterIds = document.getAssets().stream()
                .filter(asset -> "character".equals(asset.getAssetType()))
                .map(AidRolePropScene::getId).filter(Objects::nonNull).toList();
        if (!characterIds.isEmpty()) {
            Map<Long, RoleVoiceBindingVO> voiceViews =
                    roleVoiceBindingBusinessService.queryByAssetIds(characterIds, project.getUserId());
            document.setVoiceBindingViews(characterIds.stream().map(voiceViews::get)
                    .filter(Objects::nonNull).toList());
        }
        document.setGenerationConfigs(projectGenConfigMapper.selectList(Wrappers.<AidProjectGenConfig>lambdaQuery()
                .eq(AidProjectGenConfig::getProjectId, projectId).eq(AidProjectGenConfig::getDelFlag, NORMAL)
                .orderByAsc(AidProjectGenConfig::getId)));
        List<AidEpisodeEditor> editorRows = editorService.list(Wrappers.<AidEpisodeEditor>lambdaQuery()
                .eq(AidEpisodeEditor::getProjectId, projectId).eq(AidEpisodeEditor::getDelFlag, NORMAL)
                .orderByAsc(AidEpisodeEditor::getEpisodeId, AidEpisodeEditor::getId));
        Map<Long, AidEpisodeEditor> latestEditorByEpisode = new LinkedHashMap<>();
        editorRows.forEach(row -> latestEditorByEpisode.put(row.getEpisodeId(), row));
        document.setEditors(new ArrayList<>(latestEditorByEpisode.values()));
        sanitizeDocument(document);
        return document;
    }

    private void freezeLipSyncStatuses(ProjectSnapshotDocument document)
    {
        List<Long> taskIds = document.getAudioRecords().stream()
                .map(AidAudioRecord::getSyncMediaTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, AidMediaTask> tasks = new HashMap<>();
        if (!taskIds.isEmpty()) {
            mediaTaskService.list(Wrappers.<AidMediaTask>lambdaQuery()
                    .select(AidMediaTask::getId, AidMediaTask::getStatus, AidMediaTask::getOssUrl)
                    .in(AidMediaTask::getId, taskIds))
                    .forEach(task -> tasks.put(task.getId(), task));
        }
        Map<Long, String> statuses = new LinkedHashMap<>();
        for (AidAudioRecord record : document.getAudioRecords()) {
            if (record.getId() == null || !Objects.equals(record.getEnableLipSync(), 1)) {
                continue;
            }
            String status;
            if (StrUtil.isNotBlank(record.getSyncVideoUrl())) {
                status = MediaTaskStatus.SUCCEEDED.name();
            } else {
                AidMediaTask task = tasks.get(record.getSyncMediaTaskId());
                if (task != null && MediaTaskStatus.FAILED.name().equals(task.getStatus())) {
                    status = MediaTaskStatus.FAILED.name();
                } else if (task != null && MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())
                        && StrUtil.isNotBlank(task.getOssUrl())) {
                    status = MediaTaskStatus.SUCCEEDED.name();
                } else {
                    status = MediaTaskStatus.PROCESSING.name();
                }
            }
            statuses.put(record.getId(), status);
        }
        document.setAudioLipSyncStatuses(statuses);
    }

    private String rebuildDigest(ProjectSnapshotDocument source)
    {
        ProjectSnapshotDocument document = JSON.parseObject(JSON.toJSONString(source), ProjectSnapshotDocument.class);
        normalizeProjectPublicationMetadata(document);
        canonicalizeDigestMaps(document);
        return sha256(JSON.toJSONString(document));
    }

    private String approvedContentDigest(ProjectSnapshotDocument source)
    {
        ProjectSnapshotDocument document = JSON.parseObject(JSON.toJSONString(source), ProjectSnapshotDocument.class);
        normalizeProjectPublicationMetadata(document);
        document.getFormImages().removeIf(row -> !IMAGE_COMPLETED.equals(row.getImageStatus()));
        document.getGenerationRecords().removeIf(row -> !Objects.equals(row.getStatus(), GEN_SUCCESS));
        document.getAudioRecords().removeIf(row -> !AUDIO_SUCCEEDED.equals(row.getStatus()));
        document.setVoiceBindingViews(new ArrayList<>());
        document.setAudioLipSyncStatuses(new LinkedHashMap<>());
        document.getEditors().forEach(editor -> {
            editor.setFinalVideoUrl(null);
            editor.setExportStatus(0);
            editor.setExportProgress(0);
        });
        clearUpdateMetadata(document.getProject());
        document.getEpisodes().forEach(this::clearUpdateMetadata);
        document.getScripts().forEach(this::clearUpdateMetadata);
        document.getAssets().forEach(this::clearUpdateMetadata);
        document.getForms().forEach(this::clearUpdateMetadata);
        document.getFormImages().forEach(this::clearUpdateMetadata);
        document.getScenePlots().forEach(this::clearUpdateMetadata);
        document.getStoryboards().forEach(this::clearUpdateMetadata);
        document.getGenerationRecords().forEach(this::clearUpdateMetadata);
        document.getAudioRecords().forEach(this::clearUpdateMetadata);
        document.getAudioAssets().forEach(this::clearUpdateMetadata);
        document.getVoiceBindings().forEach(this::clearUpdateMetadata);
        document.getGenerationConfigs().forEach(this::clearUpdateMetadata);
        document.getEditors().forEach(this::clearUpdateMetadata);
        canonicalizeDigestOrder(document);
        return sha256(JSON.toJSONString(document));
    }

    private void normalizeProjectPublicationMetadata(ProjectSnapshotDocument document)
    {
        AidComicProject project = document.getProject();
        if (project != null) {
            project.setPublishedSnapshotId(null);
            project.setIsPublic(null);
            project.setPublishTime(null);
            project.setAllowPreview(null);
            project.setAllowCopy(null);
            project.setCopyLabel(null);
            project.setCopyCount(null);
            project.setStatusReason(null);
            project.setUpdateBy(null);
            project.setUpdateTime(null);
        }
        document.getEpisodes().forEach(episode -> episode.setStatusReason(null));
    }

    private void canonicalizeDigestOrder(ProjectSnapshotDocument document)
    {
        sortById(document.getEpisodes(), AidComicEpisode::getId);
        sortById(document.getScripts(), AidComicScript::getId);
        sortById(document.getAssets(), AidRolePropScene::getId);
        sortById(document.getForms(), AidRolePropSceneForm::getId);
        sortById(document.getFormImages(), AidRolePropSceneFormImage::getId);
        sortById(document.getScenePlots(), AidScenePlot::getId);
        sortById(document.getStoryboards(), AidStoryboard::getId);
        sortById(document.getGenerationRecords(), AidGenRecord::getId);
        sortById(document.getAudioRecords(), AidAudioRecord::getId);
        sortById(document.getAudioAssets(), AidAudioAsset::getId);
        sortById(document.getVoiceBindings(), AidRoleVoiceBinding::getId);
        sortById(document.getVoiceBindingViews(), RoleVoiceBindingVO::getBindingId);
        sortById(document.getGenerationConfigs(), AidProjectGenConfig::getId);
        sortById(document.getEditors(), AidEpisodeEditor::getId);
        canonicalizeDigestMaps(document);
    }

    private void canonicalizeDigestMaps(ProjectSnapshotDocument document)
    {
        if (document.getAudioLipSyncStatuses() != null && !document.getAudioLipSyncStatuses().isEmpty()) {
            Map<Long, String> sortedStatuses = new LinkedHashMap<>();
            document.getAudioLipSyncStatuses().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.nullsLast(Long::compareTo)))
                    .forEachOrdered(entry -> sortedStatuses.put(entry.getKey(), entry.getValue()));
            document.setAudioLipSyncStatuses(sortedStatuses);
        }
    }

    private <T> void sortById(List<T> rows, Function<T, Long> idExtractor)
    {
        if (rows != null && rows.size() > 1) {
            rows.sort(Comparator.comparing(idExtractor, Comparator.nullsLast(Long::compareTo)));
        }
    }

    private void clearUpdateMetadata(BaseEntity entity)
    {
        if (entity != null) {
            entity.setUpdateBy(null);
            entity.setUpdateTime(null);
        }
    }

    private void sanitizeDocument(ProjectSnapshotDocument document)
    {
        AidComicProject project = document.getProject();
        project.setPendingProjectName(null);
        project.setPendingProjectDesc(null);
        project.setPendingCoverUrl(null);
        for (AidComicEpisode episode : document.getEpisodes()) {
            episode.setPendingComicTitle(null);
            episode.setPendingComicDesc(null);
            episode.setPendingComicCoverUrl(null);
        }
        for (AidGenRecord record : document.getGenerationRecords()) {
            record.setTaskId(null);
            record.setBizSeq(null);
            record.setCostCredits(null);
        }
        for (AidAudioRecord record : document.getAudioRecords()) {
            record.setTtsMediaTaskId(null);
            record.setSyncMediaTaskId(null);
            record.setErrorMessage(null);
            record.setComposeBatchId(null);
        }
        for (AidAudioAsset asset : document.getAudioAssets()) {
            asset.setMediaTaskId(null);
        }
        for (AidRoleVoiceBinding binding : document.getVoiceBindings()) {
            binding.setReferenceAudioId(null);
            binding.setReferenceAudioUrl(null);
            binding.setReferenceAudioDurationMs(null);
        }
        for (RoleVoiceBindingVO binding : document.getVoiceBindingViews()) {
            binding.setReferenceAudioId(null);
            binding.setReferenceAudioUrl(null);
            binding.setReferenceAudioDurationMs(null);
        }
        for (AidEpisodeEditor editor : document.getEditors()) {
            editor.setFinalVideoFingerprint(null);
            editor.setPendingVideoUrl(null);
            editor.setPendingVideoFingerprint(null);
            editor.setExportTaskId(null);
            editor.setErrorMsg(null);
            editor.setExportFingerprint(null);
        }
    }

    private int countNodes(ProjectSnapshotDocument d)
    {
        return 7 + d.getEpisodes().size() + d.getScripts().size() + d.getAssets().size()
                + d.getForms().size() + d.getFormImages().size() + d.getScenePlots().size()
                + d.getStoryboards().size() + d.getGenerationRecords().size()
                + d.getAudioRecords().size() + d.getAudioAssets().size() + d.getVoiceBindings().size()
                + d.getVoiceBindingViews().size()
                + d.getGenerationConfigs().size() + d.getEditors().size();
    }

    /** 清理超过保留窗口且没有复制血缘引用的历史快照。 */
    private void pruneHistoricalSnapshots(Long projectId, Long currentSnapshotId)
    {
        List<AidProjectPublishSnapshot> snapshots = snapshotMapper.selectList(
                Wrappers.<AidProjectPublishSnapshot>lambdaQuery()
                        .select(AidProjectPublishSnapshot::getId, AidProjectPublishSnapshot::getCreateTime)
                        .eq(AidProjectPublishSnapshot::getProjectId, projectId)
                        .orderByDesc(AidProjectPublishSnapshot::getId));
        if (snapshots.size() <= SNAPSHOT_RETENTION_COUNT) {
            return;
        }
        Date cutoff = new Date(System.currentTimeMillis()
                - TimeUnit.DAYS.toMillis(SNAPSHOT_RETENTION_DAYS));
        List<Long> candidates = snapshots.stream()
                .skip(SNAPSHOT_RETENTION_COUNT)
                .filter(row -> !Objects.equals(row.getId(), currentSnapshotId))
                .filter(row -> row.getCreateTime() != null && row.getCreateTime().before(cutoff))
                .map(AidProjectPublishSnapshot::getId)
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        Set<Long> referenced = new LinkedHashSet<>();
        copyRecordMapper.selectList(Wrappers.<AidProjectCopyRecord>lambdaQuery()
                .select(AidProjectCopyRecord::getSourceSnapshotId)
                .in(AidProjectCopyRecord::getSourceSnapshotId, candidates))
                .forEach(row -> referenced.add(row.getSourceSnapshotId()));
        List<Long> removable = candidates.stream().filter(id -> !referenced.contains(id)).toList();
        if (removable.isEmpty()) {
            return;
        }
        snapshotMediaMapper.delete(Wrappers.<AidProjectPublishSnapshotMedia>lambdaQuery()
                .in(AidProjectPublishSnapshotMedia::getSnapshotId, removable));
        snapshotMapper.delete(Wrappers.<AidProjectPublishSnapshot>lambdaQuery()
                .in(AidProjectPublishSnapshot::getId, removable));
        log.info("项目历史发布快照已清理: projectId={}, count={}", projectId, removable.size());
    }

    private void deleteProjectSnapshotData(Long projectId)
    {
        List<AidProjectCopyRecord> targetRecords = copyRecordMapper.selectList(
                Wrappers.<AidProjectCopyRecord>lambdaQuery()
                        .select(AidProjectCopyRecord::getId, AidProjectCopyRecord::getSourceSnapshotId)
                        .eq(AidProjectCopyRecord::getTargetProjectId, projectId));
        Set<Long> candidates = new LinkedHashSet<>();
        targetRecords.forEach(row -> candidates.add(row.getSourceSnapshotId()));
        if (!targetRecords.isEmpty()) {
            copyRecordMapper.delete(Wrappers.<AidProjectCopyRecord>lambdaQuery()
                    .eq(AidProjectCopyRecord::getTargetProjectId, projectId));
        }

        snapshotMapper.selectList(Wrappers.<AidProjectPublishSnapshot>lambdaQuery()
                .select(AidProjectPublishSnapshot::getId)
                .eq(AidProjectPublishSnapshot::getProjectId, projectId))
                .forEach(row -> candidates.add(row.getId()));
        candidates.remove(null);
        if (candidates.isEmpty()) {
            return;
        }

        removeUnprotectedSnapshots(candidates, projectId, projectId);
    }

    private void removeUnprotectedSnapshots(Set<Long> candidates, Long excludedProjectId, Long logProjectId)
    {
        candidates.remove(null);
        if (candidates.isEmpty()) {
            return;
        }
        Set<Long> protectedIds = new LinkedHashSet<>();
        copyRecordMapper.selectList(Wrappers.<AidProjectCopyRecord>lambdaQuery()
                .select(AidProjectCopyRecord::getSourceSnapshotId)
                .in(AidProjectCopyRecord::getSourceSnapshotId, candidates))
                .forEach(row -> protectedIds.add(row.getSourceSnapshotId()));
        projectService.list(Wrappers.<AidComicProject>lambdaQuery()
                .select(AidComicProject::getPublishedSnapshotId, AidComicProject::getSourceSnapshotId)
                .ne(excludedProjectId != null, AidComicProject::getId, excludedProjectId)
                .eq(AidComicProject::getDelFlag, NORMAL)
                .and(wrapper -> wrapper.in(AidComicProject::getPublishedSnapshotId, candidates)
                        .or().in(AidComicProject::getSourceSnapshotId, candidates)))
                .forEach(row -> {
                    protectedIds.add(row.getPublishedSnapshotId());
                    protectedIds.add(row.getSourceSnapshotId());
                });
        protectedIds.remove(null);
        List<Long> removable = candidates.stream().filter(id -> !protectedIds.contains(id)).toList();
        if (removable.isEmpty()) {
            return;
        }
        snapshotMediaMapper.delete(Wrappers.<AidProjectPublishSnapshotMedia>lambdaQuery()
                .in(AidProjectPublishSnapshotMedia::getSnapshotId, removable));
        snapshotMapper.delete(Wrappers.<AidProjectPublishSnapshot>lambdaQuery()
                .in(AidProjectPublishSnapshot::getId, removable));
        log.info("项目删除已清理发布快照: projectId={}, count={}", logProjectId, removable.size());
    }

    private void saveSnapshotMediaReferences(Long snapshotId, ProjectSnapshotDocument document)
    {
        Set<String> urls = new LinkedHashSet<>();
        addMedia(urls, document.getProject().getCoverUrl());
        document.getEpisodes().forEach(row -> addMedia(urls, row.getComicCoverUrl()));
        for (AidRolePropSceneFormImage row : document.getFormImages()) {
            addMedia(urls, row.getImageUrl());
            collectEmbeddedMedia(urls, row.getReferenceImages());
        }
        for (AidGenRecord row : document.getGenerationRecords()) {
            addMedia(urls, row.getFileUrl());
            collectGenerationReferenceMedia(urls, row.getGenParams());
        }
        for (AidAudioRecord row : document.getAudioRecords()) {
            addMedia(urls, row.getAudioUrl());
            addMedia(urls, row.getSyncVideoUrl());
        }
        document.getAudioAssets().forEach(row -> addMedia(urls, row.getAudioUrl()));
        for (AidRoleVoiceBinding row : document.getVoiceBindings()) {
            addMedia(urls, row.getAvatarUrl());
            addMedia(urls, row.getSampleUrl());
            addMedia(urls, row.getReferenceAudioUrl());
        }
        for (RoleVoiceBindingVO row : document.getVoiceBindingViews()) {
            addMedia(urls, row.getAvatarUrl());
            addMedia(urls, row.getSampleUrl());
        }
        for (AidEpisodeEditor row : document.getEditors()) {
            addMedia(urls, row.getCoverUrl());
            addMedia(urls, row.getFinalVideoUrl());
            collectEmbeddedMedia(urls, row.getTimelineJson());
        }
        for (String url : urls) {
            AidProjectPublishSnapshotMedia reference = new AidProjectPublishSnapshotMedia();
            reference.setSnapshotId(snapshotId);
            reference.setMediaUrlHash(sha256(url));
            reference.setMediaUrl(url);
            if (snapshotMediaMapper.insert(reference) != 1) {
                log.error("发布快照媒体索引保存失败: snapshotId={}", snapshotId);
                throw new ServiceException("快照保存失败");
            }
        }
    }

    private void collectGenerationReferenceMedia(Set<String> urls, String genParams)
    {
        if (StrUtil.isBlank(genParams)) {
            return;
        }
        try {
            Object parsed = JSON.parse(genParams);
            if (!(parsed instanceof Map<?, ?> root)
                    || !(root.get("referenceManifest") instanceof List<?> manifest)) {
                return;
            }
            for (Object item : manifest) {
                if (item instanceof Map<?, ?> reference) {
                    Object url = reference.get("url");
                    if (url instanceof String text) {
                        addMedia(urls, text);
                    }
                }
            }
        } catch (Exception ignored) {
            // 历史生成参数解析失败时不影响快照创建，显式媒体字段仍会进入引用保护。
        }
    }

    private void collectEmbeddedMedia(Set<String> urls, String json)
    {
        if (StrUtil.isBlank(json)) { return; }
        try {
            collectJsonMedia(urls, JSON.parse(json));
        } catch (Exception ignored) {
            // 旧数据中的非 JSON 扩展不参与媒体保护；标准 URL 字段仍由上方显式收集。
        }
    }

    private void collectJsonMedia(Set<String> urls, Object value)
    {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectJsonMedia(urls, item));
        } else if (value instanceof List<?> list) {
            list.forEach(item -> collectJsonMedia(urls, item));
        } else if (value instanceof String text) {
            addMedia(urls, text);
        }
    }

    private void addMedia(Set<String> urls, String value)
    {
        if (StrUtil.isBlank(value)) { return; }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase();
        if (trimmed.startsWith("/") || lower.startsWith("http://") || lower.startsWith("https://")) {
            String normalized = mediaUrlResolver.toRelativePath(trimmed);
            if (StrUtil.isNotBlank(normalized)) {
                urls.add(normalized.trim());
            }
        }
    }

    private AidComicProject requirePublicProject(Long projectId, boolean requireCopy, String lockClause)
    {
        LambdaQueryWrapper<AidComicProject> wrapper = Wrappers.<AidComicProject>lambdaQuery()
                .eq(AidComicProject::getId, projectId)
                .eq(AidComicProject::getDelFlag, NORMAL)
                .eq(AidComicProject::getIsPublic, YES);
        if (StrUtil.isNotBlank(lockClause)) {
            wrapper.last(lockClause);
        }
        AidComicProject project = projectService.getOne(wrapper);
        if (project == null) {
            log.info("读取发布流程失败，项目未公开: projectId={}", projectId);
            throw new ServiceException("项目已下架");
        }
        ProjectCaseAccessSummary access = projectCaseAccessService.evaluate(ProjectCaseAccessSubject.builder()
                .projectId(project.getId())
                .projectType(project.getProjectType())
                .isPublic(project.getIsPublic())
                .delFlag(project.getDelFlag())
                .status(project.getStatus())
                .allowPreview(project.getAllowPreview())
                .allowCopy(project.getAllowCopy())
                .publishedSnapshotId(project.getPublishedSnapshotId())
                .build());
        if (requireCopy && !Boolean.TRUE.equals(access.getCanCopyProject())) {
            rejectCaseAccess(projectId, access.getCopyProjectReasonCode(), true);
        }
        if (!requireCopy && !Boolean.TRUE.equals(access.getCanViewProjectContent())) {
            rejectCaseAccess(projectId, access.getViewProjectContentReasonCode(), false);
        }
        return project;
    }

    /** 将稳定原因码映射为公开接口安全提示。 */
    private void rejectCaseAccess(Long projectId, String reasonCode, boolean copy)
    {
        log.info("案例项目访问拒绝: projectId={}, operation={}, reason={}",
                projectId, copy ? "COPY" : "PREVIEW", reasonCode);
        if (ProjectCaseAccessReason.PROJECT_TYPE_UNSUPPORTED.name().equals(reasonCode)) {
            throw new ServiceException("仅支持电影复制");
        }
        if (ProjectCaseAccessReason.PROJECT_COPY_DISABLED.name().equals(reasonCode)) {
            throw new ServiceException("未开放复制");
        }
        if (ProjectCaseAccessReason.PROJECT_CONTENT_SHARING_DISABLED.name().equals(reasonCode)
                || ProjectCaseAccessReason.PROJECT_CONTENT_NOT_VIEWABLE.name().equals(reasonCode)) {
            throw new ServiceException("未开放预览");
        }
        if (ProjectCaseAccessReason.NO_APPROVED_SNAPSHOT.name().equals(reasonCode)) {
            throw new ServiceException("暂无发布版本");
        }
        if (ProjectCaseAccessReason.PUBLISHED_SNAPSHOT_UNAVAILABLE.name().equals(reasonCode)) {
            throw new ServiceException("发布版本无效");
        }
        throw new ServiceException("项目已下架");
    }

    private AidProjectPublishSnapshot requireCurrentSnapshot(AidComicProject project)
    {
        if (project.getPublishedSnapshotId() == null) {
            log.info("读取发布流程失败，无发布指针: projectId={}", project.getId());
            throw new ServiceException("暂无发布版本");
        }
        AidProjectPublishSnapshot snapshot = snapshotMapper.selectOne(
                Wrappers.<AidProjectPublishSnapshot>lambdaQuery()
                        .eq(AidProjectPublishSnapshot::getId, project.getPublishedSnapshotId())
                        .eq(AidProjectPublishSnapshot::getProjectId, project.getId()));
        if (snapshot == null) {
            log.error("读取发布流程失败，发布版本不存在: projectId={}, snapshotId={}",
                    project.getId(), project.getPublishedSnapshotId());
            throw new ServiceException("发布版本不存在");
        }
        return snapshot;
    }

    private ProjectSnapshotDocument parseDocument(AidProjectPublishSnapshot snapshot)
    {
        if (!Objects.equals(snapshot.getContentHash(), sha256(snapshot.getSnapshotJson()))) {
            log.error("发布版本内容校验失败: snapshotId={}", snapshot.getId());
            throw new ServiceException("发布版本校验失败");
        }
        ProjectSnapshotDocument document;
        try {
            document = JSON.parseObject(snapshot.getSnapshotJson(), ProjectSnapshotDocument.class);
        } catch (Exception e) {
            log.error("发布版本内容解析失败: snapshotId={}", snapshot.getId(), e);
            throw new ServiceException("发布版本不兼容");
        }
        if (document == null || !Objects.equals(document.getSchemaVersion(), SCHEMA_VERSION)) {
            log.error("发布版本结构不兼容: snapshotId={}, expectedSchema={}", snapshot.getId(), SCHEMA_VERSION);
            throw new ServiceException("发布版本不兼容");
        }
        return document;
    }

    private AidProjectCopyRecord findCopyRecord(Long userId, String requestId)
    {
        return copyRecordMapper.selectOne(Wrappers.<AidProjectCopyRecord>lambdaQuery()
                .eq(AidProjectCopyRecord::getUserId, userId)
                .eq(AidProjectCopyRecord::getRequestId, requestId));
    }

    private void validateMovieDocument(ProjectSnapshotDocument document, AidComicProject source,
                                       AidProjectPublishSnapshot snapshot)
    {
        if (document.getProject() == null
                || !Objects.equals(document.getProject().getId(), source.getId())
                || !Objects.equals(document.getProject().getProjectType(), MOVIE)) {
            invalidSnapshot(snapshot.getId(), "project", source.getId());
        }
        Set<Long> assetIds = ids(document.getAssets().stream().map(AidRolePropScene::getId).toList());
        Set<Long> formIds = ids(document.getForms().stream().map(AidRolePropSceneForm::getId).toList());
        Set<Long> imageIds = ids(document.getFormImages().stream().map(AidRolePropSceneFormImage::getId).toList());
        Set<Long> storyboardIds = ids(document.getStoryboards().stream().map(AidStoryboard::getId).toList());
        Set<Long> generationIds = ids(document.getGenerationRecords().stream().map(AidGenRecord::getId).toList());
        Set<Long> audioIds = ids(document.getAudioRecords().stream().map(AidAudioRecord::getId).toList());
        document.getForms().forEach(row -> requireReference(assetIds, row.getAssetId(), "form.asset", snapshot));
        document.getFormImages().forEach(row -> {
            requireReference(formIds, row.getFormId(), "image.form", snapshot);
            requireReference(assetIds, row.getAssetId(), "image.asset", snapshot);
            requireReference(imageIds, row.getSplitParentImageId(), "image.parent", snapshot);
        });
        document.getScenePlots().forEach(row ->
                requireReference(assetIds, row.getSceneId(), "plot.scene", snapshot));
        document.getStoryboards().forEach(row -> {
            requireReference(assetIds, row.getSourceSceneId(), "storyboard.scene", snapshot);
            requireReference(generationIds, row.getFinalImageId(), "storyboard.image", snapshot);
            requireReference(generationIds, row.getFinalVideoId(), "storyboard.video", snapshot);
            requireReference(audioIds, row.getFinalAudioId(), "storyboard.audio", snapshot);
        });
        document.getGenerationRecords().forEach(row -> {
            requireReference(storyboardIds, row.getStoryboardId(), "generation.storyboard", snapshot);
            requireReference(generationIds, row.getBaseImageId(), "generation.base", snapshot);
            requireReference(generationIds, row.getFirstImageId(), "generation.first", snapshot);
            requireReference(generationIds, row.getLastImageId(), "generation.last", snapshot);
        });
        document.getAudioRecords().forEach(row ->
                requireReference(storyboardIds, row.getStoryboardId(), "audio.storyboard", snapshot));
        document.getVoiceBindings().forEach(row ->
                requireReference(assetIds, row.getAssetId(), "voice.asset", snapshot));
    }

    private Set<Long> ids(List<Long> values)
    {
        Set<Long> result = new LinkedHashSet<>(values);
        result.remove(null);
        return result;
    }

    private void requireReference(Set<Long> ids, Long referencedId, String relation,
                                  AidProjectPublishSnapshot snapshot)
    {
        if (referencedId != null && !ids.contains(referencedId)) {
            invalidSnapshot(snapshot.getId(), relation, referencedId);
        }
    }

    private void invalidSnapshot(Long snapshotId, String relation, Long referencedId)
    {
        log.error("发布版本引用无效: snapshotId={}, relation={}, referencedId={}",
                snapshotId, relation, referencedId);
        throw new ServiceException("发布版本无效");
    }

    private ProjectSnapshotCopyResultVO reuseCopyResult(AidProjectCopyRecord record, Long requestedProjectId)
    {
        if (!Objects.equals(record.getSourceProjectId(), requestedProjectId)) {
            log.info("复制请求标识冲突: requestId={}, sourceProjectId={}, requestedProjectId={}",
                    record.getRequestId(), record.getSourceProjectId(), requestedProjectId);
            throw new ServiceException("请求标识已占用");
        }
        return ProjectSnapshotCopyResultVO.builder()
                .projectId(record.getTargetProjectId())
                .sourceProjectId(record.getSourceProjectId())
                .sourceSnapshotId(record.getSourceSnapshotId())
                .copyLabel(DEFAULT_COPY_LABEL)
                .reused(true)
                .build();
    }

    private Long copyMovieDocument(ProjectSnapshotDocument d, AidComicProject source,
                                   AidProjectPublishSnapshot snapshot, ProjectSnapshotCopyRequest request,
                                   Long userId, String operator)
    {
        Date now = DateUtils.getNowDate();
        AidComicProject target = cloneOf(d.getProject(), AidComicProject::new);
        target.setId(null);
        target.setUserId(userId);
        target.setProjectName(copyProjectName(request.getProjectName(), source.getProjectName()));
        target.setPendingProjectName(null);
        target.setPendingProjectDesc(null);
        target.setPendingCoverUrl(null);
        target.setCurrentStep(PREVIEW_STEP);
        target.setStatus(PROJECT_STATUS_DRAFT);
        target.setStatusReason(null);
        target.setIsPublic(NORMAL);
        target.setPublishTime(null);
        target.setAllowPreview(NORMAL);
        target.setAllowCopy(NORMAL);
        target.setPublishedSnapshotId(null);
        target.setSourceProjectId(source.getId());
        target.setSourceSnapshotId(snapshot.getId());
        target.setCopyLabel(DEFAULT_COPY_LABEL);
        target.setCopyCount(0L);
        // 自定义风格资产属于原作者；复制工程保留已固化提示词，但不伪造对原资产记录的所有权。
        if (Objects.equals("custom", target.getStyleSource())) {
            target.setStyleSource(null);
            target.setStyleAssetId(null);
        }
        resetBase(target, now, operator);
        ensureCopySaved(projectService.save(target) && target.getId() != null, "project", source.getId());
        Long targetId = target.getId();

        for (AidComicScript old : d.getScripts()) {
            AidComicScript row = cloneOf(old, AidComicScript::new);
            row.setId(null); row.setProjectId(targetId); row.setEpisodeId(MOVIE_EPISODE_ID); row.setUserId(userId);
            resetBase(row, now, operator);
            ensureCopySaved(scriptService.save(row), "script", source.getId());
        }

        Map<Long, Long> assetIds = new HashMap<>();
        for (AidRolePropScene old : d.getAssets()) {
            AidRolePropScene row = cloneOf(old, AidRolePropScene::new);
            Long oldId = row.getId();
            row.setId(null); row.setProjectId(targetId); row.setEpisodeId(MOVIE_EPISODE_ID); row.setUserId(userId);
            row.setDeleteReason(null); row.setDeletedAt(null); row.setDeleteTaskId(null);
            resetBase(row, now, operator);
            ensureCopySaved(assetService.save(row) && row.getId() != null, "asset", source.getId());
            assetIds.put(oldId, row.getId());
        }

        Map<Long, Long> formIds = new HashMap<>();
        for (AidRolePropSceneForm old : d.getForms()) {
            AidRolePropSceneForm row = cloneOf(old, AidRolePropSceneForm::new);
            Long oldId = row.getId();
            row.setId(null); row.setAssetId(assetIds.get(old.getAssetId())); row.setProjectId(targetId);
            row.setEpisodeId(MOVIE_EPISODE_ID); row.setUserId(userId);
            resetBase(row, now, operator);
            ensureCopySaved(formService.save(row) && row.getId() != null, "asset-form", source.getId());
            formIds.put(oldId, row.getId());
        }

        List<AidRolePropSceneFormImage> copyableImages = d.getFormImages().stream()
                .filter(row -> IMAGE_COMPLETED.equals(row.getImageStatus())).toList();
        Map<Long, Long> imageIds = new HashMap<>();
        List<AidRolePropSceneFormImage> copiedImages = new ArrayList<>();
        for (AidRolePropSceneFormImage old : copyableImages) {
            AidRolePropSceneFormImage row = cloneOf(old, AidRolePropSceneFormImage::new);
            Long oldId = row.getId();
            row.setId(null); row.setFormId(formIds.get(old.getFormId())); row.setAssetId(assetIds.get(old.getAssetId()));
            row.setProjectId(targetId); row.setEpisodeId(MOVIE_EPISODE_ID); row.setUserId(userId);
            row.setSplitParentImageId(null); row.setBatchNo(null); row.setFailReason(null);
            resetBase(row, now, operator);
            ensureCopySaved(formImageService.save(row) && row.getId() != null, "form-image", source.getId());
            imageIds.put(oldId, row.getId()); copiedImages.add(row);
        }
        for (int i = 0; i < copyableImages.size(); i++) {
            Long parent = imageIds.get(copyableImages.get(i).getSplitParentImageId());
            if (parent != null) {
                AidRolePropSceneFormImage row = copiedImages.get(i);
                row.setSplitParentImageId(parent);
                ensureCopySaved(formImageService.updateById(row), "form-parent", source.getId());
            }
        }

        for (AidScenePlot old : d.getScenePlots()) {
            AidScenePlot row = cloneOf(old, AidScenePlot::new);
            row.setId(null); row.setSceneId(assetIds.get(old.getSceneId())); row.setProjectId(targetId);
            row.setEpisodeId(MOVIE_EPISODE_ID); row.setUserId(userId);
            resetBase(row, now, operator);
            ensureCopySaved(scenePlotService.save(row), "scene-plot", source.getId());
        }

        Map<Long, Long> storyboardIds = new HashMap<>();
        List<AidStoryboard> copiedStoryboards = new ArrayList<>();
        for (AidStoryboard old : d.getStoryboards()) {
            AidStoryboard row = cloneOf(old, AidStoryboard::new);
            Long oldId = row.getId();
            row.setId(null); row.setProjectId(targetId); row.setEpisodeId(MOVIE_EPISODE_ID); row.setUserId(userId);
            row.setSourceSceneId(assetIds.get(old.getSourceSceneId())); row.setBatchId(null);
            row.setFinalImageId(null); row.setFinalVideoId(null); row.setFinalAudioId(null);
            resetBase(row, now, operator);
            ensureCopySaved(storyboardService.save(row) && row.getId() != null, "storyboard", source.getId());
            storyboardIds.put(oldId, row.getId()); copiedStoryboards.add(row);
        }

        List<AidGenRecord> copyableGenerationRecords = d.getGenerationRecords().stream()
                .filter(row -> Objects.equals(row.getStatus(), GEN_SUCCESS)).toList();
        Map<Long, Long> genIds = new HashMap<>();
        List<AidGenRecord> copiedGen = new ArrayList<>();
        for (AidGenRecord old : copyableGenerationRecords) {
            AidGenRecord row = cloneOf(old, AidGenRecord::new);
            Long oldId = row.getId();
            row.setId(null); row.setUserId(userId); row.setProjectId(targetId); row.setEpisodeId(MOVIE_EPISODE_ID);
            row.setStoryboardId(storyboardIds.get(old.getStoryboardId())); row.setTaskId(null); row.setBizSeq(null);
            row.setCostCredits(null); row.setBaseImageId(null); row.setFirstImageId(null); row.setLastImageId(null);
            resetBase(row, now, operator);
            ensureCopySaved(genRecordService.save(row) && row.getId() != null, "gen-record", source.getId());
            genIds.put(oldId, row.getId()); copiedGen.add(row);
        }
        for (int i = 0; i < copyableGenerationRecords.size(); i++) {
            AidGenRecord old = copyableGenerationRecords.get(i);
            AidGenRecord row = copiedGen.get(i);
            row.setBaseImageId(genIds.get(old.getBaseImageId()));
            row.setFirstImageId(genIds.get(old.getFirstImageId()));
            row.setLastImageId(genIds.get(old.getLastImageId()));
            ensureCopySaved(genRecordService.updateById(row), "gen-reference", source.getId());
        }

        List<AidAudioRecord> copyableAudioRecords = d.getAudioRecords().stream()
                .filter(row -> AUDIO_SUCCEEDED.equals(row.getStatus())).toList();
        Map<Long, Long> audioIds = new HashMap<>();
        for (AidAudioRecord old : copyableAudioRecords) {
            AidAudioRecord row = cloneOf(old, AidAudioRecord::new);
            Long oldId = row.getId();
            row.setId(null); row.setUserId(userId); row.setProjectId(targetId); row.setEpisodeId(MOVIE_EPISODE_ID);
            row.setStoryboardId(storyboardIds.get(old.getStoryboardId())); row.setTtsMediaTaskId(null);
            row.setSyncMediaTaskId(null); row.setErrorMessage(null); row.setComposeBatchId(null);
            resetBase(row, now, operator);
            ensureCopySaved(audioRecordService.save(row) && row.getId() != null, "audio-record", source.getId());
            audioIds.put(oldId, row.getId());
        }

        for (AidAudioAsset old : d.getAudioAssets()) {
            Long newAudioRecordId = audioIds.get(old.getAudioRecordId());
            if (newAudioRecordId == null) { continue; }
            AidAudioAsset row = cloneOf(old, AidAudioAsset::new);
            row.setId(null); row.setUserId(userId); row.setProjectId(targetId); row.setEpisodeId(MOVIE_EPISODE_ID);
            row.setStoryboardId(storyboardIds.get(old.getStoryboardId())); row.setAudioRecordId(newAudioRecordId);
            row.setMediaTaskId(null); resetBase(row, now, operator);
            ensureCopySaved(audioAssetService.save(row), "audio-asset", source.getId());
        }

        for (int i = 0; i < d.getStoryboards().size(); i++) {
            AidStoryboard old = d.getStoryboards().get(i);
            AidStoryboard row = copiedStoryboards.get(i);
            row.setFinalImageId(genIds.get(old.getFinalImageId()));
            row.setFinalVideoId(genIds.get(old.getFinalVideoId()));
            row.setFinalAudioId(audioIds.get(old.getFinalAudioId()));
            ensureCopySaved(storyboardService.updateById(row), "storyboard-final", source.getId());
        }

        for (AidRoleVoiceBinding old : d.getVoiceBindings()) {
            AidRoleVoiceBinding row = cloneOf(old, AidRoleVoiceBinding::new);
            row.setId(null); row.setAssetId(assetIds.get(old.getAssetId())); row.setProjectId(targetId);
            row.setEpisodeId(MOVIE_EPISODE_ID); row.setUserId(userId); row.setReferenceAudioId(null);
            row.setReferenceAudioUrl(null); row.setReferenceAudioDurationMs(null);
            resetBase(row, now, operator);
            ensureCopySaved(voiceBindingService.save(row), "voice-binding", source.getId());
        }

        for (AidProjectGenConfig old : d.getGenerationConfigs()) {
            AidProjectGenConfig row = cloneOf(old, AidProjectGenConfig::new);
            row.setId(null); row.setProjectId(targetId); row.setUserId(userId);
            resetBase(row, now, operator);
            ensureCopySaved(projectGenConfigMapper.insert(row) == 1, "gen-config", source.getId());
        }

        d.getEditors().stream().filter(it -> Objects.equals(it.getEpisodeId(), MOVIE_EPISODE_ID))
                .findFirst().ifPresent(old -> {
                    AidEpisodeEditor row = cloneOf(old, AidEpisodeEditor::new);
                    row.setId(null); row.setProjectId(targetId); row.setEpisodeId(MOVIE_EPISODE_ID); row.setUserId(userId);
                    row.setTimelineJson(remapTimeline(old.getTimelineJson(), storyboardIds, genIds, audioIds));
                    row.setFinalVideoUrl(null); row.setFinalVideoFingerprint(null); row.setPendingVideoUrl(null);
                    row.setPendingVideoFingerprint(null); row.setExportStatus(0); row.setExportProgress(0);
                    row.setExportTaskId(null); row.setErrorMsg(null); row.setExportFingerprint(null);
                    resetBase(row, now, operator);
                    ensureCopySaved(editorService.save(row), "timeline", source.getId());
                });
        return targetId;
    }

    private void ensureCopySaved(boolean saved, String stage, Long sourceProjectId)
    {
        if (!saved) {
            log.error("复制工程写入失败: sourceProjectId={}, stage={}", sourceProjectId, stage);
            throw new ServiceException("复制工程失败");
        }
    }

    private ProjectSnapshotPreviewVO buildPreview(AidComicProject project, AidProjectPublishSnapshot snapshot,
                                                   ProjectSnapshotDocument d)
    {
        ProjectSnapshotStageAssembler.Contracts contracts = snapshotStageAssembler.assemble(d, snapshot.getId());
        List<ProjectSnapshotPreviewVO.FlowNode> nodes = new ArrayList<>();
        List<ProjectSnapshotPreviewVO.FlowEdge> edges = new ArrayList<>();
        String[] names = {"项目设置", "剧本创作", "素材准备", "分镜设计", "视频生成", "音画同步", "预览工程"};
        for (int i = 0; i < names.length; i++) {
            int stage = i + 1;
            String id = "stage:" + stage;
            nodes.add(node(id, "stage", String.valueOf(stage), names[i], stage,
                    "StageMarker", null, new LinkedHashMap<>(), null));
            if (stage > 1) {
                edges.add(edge("stage:" + (stage - 1), id, "next"));
            }
        }
        nodes.add(node("project:" + project.getId(), "project", "1", d.getProject().getProjectName(), 0,
                "UserProjectVO(public-projection)", "/api/user/project/detail", contracts.project(), null));
        edges.add(edge("stage:1", "project:" + project.getId(), "contains"));

        for (AidComicEpisode episode : d.getEpisodes()) {
            addItem(nodes, edges, "1", "episode", episode.getId(), episode.getComicTitle(),
                    "UserEpisodeVO(public-projection)", "/api/user/episode/list", contracts.episodes().get(episode.getId()),
                    snapshotMeta("currentStep", episode.getCurrentStep()));
        }

        for (AidComicScript script : d.getScripts()) {
            if (!contracts.scripts().containsKey(script.getId())) {
                continue;
            }
            addItem(nodes, edges, "2", "script", script.getId(), "剧本 " + script.getId(),
                    "UserScriptVO(public-projection)", "/api/user/script/detailByProject", contracts.scripts().get(script.getId()), null);
        }
        for (AidRolePropScene asset : d.getAssets()) {
            addItem(nodes, edges, "3", "asset", asset.getId(), asset.getName(),
                    "RpsAssetVO(public-projection)", "/api/user/asset/rps/list", contracts.assets().get(asset.getId()),
                    snapshotMeta("episodeId", asset.getEpisodeId()));
        }
        for (AidScenePlot plot : d.getScenePlots()) {
            addItem(nodes, edges, "3", "scene-plot", plot.getId(), "场次 " + plot.getSceneCode(),
                    "ProjectSnapshotScenePlot", null, contracts.scenePlots().get(plot.getId()), null);
        }
        for (AidStoryboard storyboard : d.getStoryboards()) {
            addItem(nodes, edges, "4", "storyboard", storyboard.getId(), storyboard.getTitle(),
                    "StoryboardVO(list,public-projection)", "/api/user/storyboard/list",
                    contracts.storyboards().get(storyboard.getId()), null);
        }
        for (AidProjectGenConfig config : d.getGenerationConfigs()) {
            addItem(nodes, edges, "5", "generation-config", config.getId(), config.getSceneCode(),
                    "ProjectGenConfigVO(frozen-selection,public-projection)", "/api/user/project/gen-config/get",
                    contracts.generationConfigs().get(config.getId()), null);
        }
        for (AidGenRecord record : d.getGenerationRecords()) {
            addItem(nodes, edges, "5", "media", record.getId(), record.getGenType() + " " + record.getId(),
                    "GenRecordVO(public-projection)", "/api/user/storyboard/record/list-by-storyboard",
                    contracts.generationRecords().get(record.getId()), null);
            if (record.getStoryboardId() != null) {
                edges.add(edge("storyboard:" + record.getStoryboardId(), "media:" + record.getId(), "produces"));
            }
        }
        for (AidAudioRecord record : d.getAudioRecords()) {
            addItem(nodes, edges, "6", "audio", record.getId(), "配音 " + record.getId(),
                    "AudioTaskVO(public-projection)", "/api/user/storyboard/audio/detail",
                    contracts.audioRecords().get(record.getId()), contracts.audioMeta().get(record.getId()));
            if (record.getStoryboardId() != null) {
                edges.add(edge("storyboard:" + record.getStoryboardId(), "audio:" + record.getId(), "voices"));
            }
        }
        for (AidRoleVoiceBinding binding : d.getVoiceBindings()) {
            if (!NORMAL.equals(binding.getStatus())) {
                continue;
            }
            addItem(nodes, edges, "6", "voice-binding", binding.getId(), binding.getVoiceName(),
                    "RoleVoiceBindingVO(public-projection)", "/api/user/asset/rps/voice/query",
                    contracts.voiceBindings().get(binding.getId()),
                    snapshotMeta("episodeId", binding.getEpisodeId()));
            if (binding.getAssetId() != null) {
                edges.add(edge("asset:" + binding.getAssetId(), "voice-binding:" + binding.getId(), "voices"));
            }
        }
        d.getEditors().forEach(editor -> {
            addItem(nodes, edges, "7", "timeline", editor.getId(), "预览时间线",
                    "EpisodeTimelineResult(public-projection)", "/api/user/episode/timeline/get",
                    contracts.timelines().get(editor.getId()), null);
        });
        return ProjectSnapshotPreviewVO.builder()
                .projectId(project.getId()).projectName(project.getProjectName()).projectType(project.getProjectType())
                .snapshotId(snapshot.getId()).revisionNo(snapshot.getRevisionNo()).schemaVersion(snapshot.getSchemaVersion())
                .snapshotHash(snapshot.getContentHash()).publishedAt(snapshot.getCreateTime())
                .allowPreview(true).allowCopy(YES.equals(project.getAllowCopy()))
                .copyLabel(effectiveCopyLabel(project)).nodes(nodes).edges(edges).build();
    }

    private void addItem(List<ProjectSnapshotPreviewVO.FlowNode> nodes,
                         List<ProjectSnapshotPreviewVO.FlowEdge> edges, String stage, String type,
                         Long sourceId, String title, String contract, String sourceEndpoint,
                         Object data, Map<String, Object> snapshotMeta)
    {
        String id = type + ":" + sourceId;
        nodes.add(node(id, type, stage, StrUtil.blankToDefault(title, type), nodes.size(),
                contract, sourceEndpoint, data, snapshotMeta));
        edges.add(edge("stage:" + stage, id, "contains"));
    }

    private ProjectSnapshotPreviewVO.FlowNode node(String id, String type, String stage, String title,
                                                   Integer order, String contract, String sourceEndpoint,
                                                   Object data, Map<String, Object> snapshotMeta)
    {
        return ProjectSnapshotPreviewVO.FlowNode.builder().id(id).type(type).stage(stage)
                .title(title).order(order).contract(contract).sourceEndpoint(sourceEndpoint)
                .data(data).snapshotMeta(snapshotMeta).build();
    }

    private ProjectSnapshotPreviewVO.FlowEdge edge(String source, String target, String relation)
    {
        return ProjectSnapshotPreviewVO.FlowEdge.builder().id(source + "->" + target)
                .source(source).target(target).relation(relation).build();
    }

    private Map<String, Object> snapshotMeta(String key, Object value)
    {
        if (value == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }

    private String effectiveCopyLabel(AidComicProject project)
    {
        return project.getSourceProjectId() == null ? null
                : StrUtil.blankToDefault(project.getCopyLabel(), DEFAULT_COPY_LABEL);
    }

    private String copyProjectName(String requested, String sourceName)
    {
        String name = StrUtil.isNotBlank(requested) ? requested.trim()
                : StrUtil.blankToDefault(sourceName, "未命名项目") + " - 副本";
        int codePoints = name.codePointCount(0, name.length());
        return codePoints <= 100 ? name : name.substring(0, name.offsetByCodePoints(0, 100));
    }

    private String remapTimeline(String timeline, Map<Long, Long> storyboardIds,
                                 Map<Long, Long> genIds, Map<Long, Long> audioIds)
    {
        if (StrUtil.isBlank(timeline)) { return timeline; }
        try {
            Object root = JSON.parse(timeline);
            remapJsonValue(root, storyboardIds, genIds, audioIds);
            return JSON.toJSONString(root);
        } catch (Exception e) {
            log.error("复制项目失败，预览时间线格式错误", e);
            throw new ServiceException("预览时间线无效");
        }
    }

    @SuppressWarnings("unchecked")
    private void remapJsonValue(Object value, Map<Long, Long> storyboardIds,
                                Map<Long, Long> genIds, Map<Long, Long> audioIds)
    {
        if (value instanceof Map<?, ?> rawMap) {
            Map<Object, Object> map = (Map<Object, Object>) rawMap;
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object current = entry.getValue();
                if (current instanceof Number number) {
                    Long oldId = number.longValue();
                    if ("storyboardId".equals(key)) { entry.setValue(storyboardIds.get(oldId)); continue; }
                    if ("genRecordId".equals(key)) { entry.setValue(genIds.get(oldId)); continue; }
                    if ("audioRecordId".equals(key)) { entry.setValue(audioIds.get(oldId)); continue; }
                }
                remapJsonValue(current, storyboardIds, genIds, audioIds);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) { remapJsonValue(item, storyboardIds, genIds, audioIds); }
        }
    }

    private <T> T cloneOf(T source, Supplier<T> supplier)
    {
        T target = supplier.get();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private void resetBase(BaseEntity entity, Date now, String operator)
    {
        entity.setSearchValue(null);
        entity.setParams(null);
        entity.setCreateBy(operator);
        entity.setCreateTime(now);
        entity.setUpdateBy(operator);
        entity.setUpdateTime(now);
        entity.setRemark(null);
    }

    private String sha256(String value)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) { result.append(String.format("%02x", item)); }
            return result.toString();
        } catch (Exception e) {
            log.error("SHA-256 初始化失败", e);
            throw new ServiceException("摘要计算失败");
        }
    }
}
