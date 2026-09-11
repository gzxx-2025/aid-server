package com.aid.project.snapshot.service.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.aid.project.snapshot.domain.AidProjectPublishSnapshot;
import com.aid.project.snapshot.mapper.AidProjectPublishSnapshotMapper;
import com.aid.project.snapshot.model.ProjectCaseAccessReason;
import com.aid.project.snapshot.model.ProjectCaseAccessSubject;
import com.aid.project.snapshot.service.IProjectCaseAccessService;
import com.aid.project.snapshot.vo.ProjectCaseAccessSummary;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;

/** 依据公开状态、作者开关和已审核快照摘要计算案例访问能力。 */
@Service
@RequiredArgsConstructor
public class ProjectCaseAccessServiceImpl implements IProjectCaseAccessService
{
    private static final String YES = "1";
    private static final String NORMAL = "0";
    private static final String MOVIE = "movie";
    private static final int AUDITING = 3;
    private static final int APPROVED = 4;
    private static final int SNAPSHOT_SCHEMA_VERSION = 1;
    private static final int MAX_NODE_COUNT = 10_000;

    private final AidProjectPublishSnapshotMapper snapshotMapper;

    @Override
    public ProjectCaseAccessSummary evaluate(ProjectCaseAccessSubject subject)
    {
        if (subject == null || subject.getProjectId() == null) {
            return unavailable();
        }
        return evaluateBatch(List.of(subject)).getOrDefault(subject.getProjectId(), unavailable());
    }

    @Override
    public Map<Long, ProjectCaseAccessSummary> evaluateBatch(List<ProjectCaseAccessSubject> subjects)
    {
        if (CollectionUtil.isEmpty(subjects)) {
            return Collections.emptyMap();
        }
        List<Long> snapshotIds = subjects.stream()
                .map(ProjectCaseAccessSubject::getPublishedSnapshotId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, AidProjectPublishSnapshot> snapshots = new HashMap<>();
        if (CollectionUtil.isNotEmpty(snapshotIds)) {
            snapshotMapper.selectList(Wrappers.<AidProjectPublishSnapshot>lambdaQuery()
                            .select(AidProjectPublishSnapshot::getId, AidProjectPublishSnapshot::getProjectId,
                                    AidProjectPublishSnapshot::getSchemaVersion,
                                    AidProjectPublishSnapshot::getContentHash,
                                    AidProjectPublishSnapshot::getNodeCount)
                            .in(AidProjectPublishSnapshot::getId, snapshotIds))
                    .forEach(snapshot -> snapshots.put(snapshot.getId(), snapshot));
        }

        Map<Long, ProjectCaseAccessSummary> result = new LinkedHashMap<>();
        for (ProjectCaseAccessSubject subject : subjects) {
            if (subject == null || subject.getProjectId() == null) {
                continue;
            }
            result.put(subject.getProjectId(), evaluate(subject, snapshots.get(subject.getPublishedSnapshotId())));
        }
        return result;
    }

    private ProjectCaseAccessSummary evaluate(ProjectCaseAccessSubject subject,
                                               AidProjectPublishSnapshot snapshot)
    {
        if (!isAvailable(subject)) {
            return unavailable();
        }
        if (subject.getPublishedSnapshotId() == null) {
            return denied(ProjectCaseAccessReason.NO_APPROVED_SNAPSHOT,
                    ProjectCaseAccessReason.NO_APPROVED_SNAPSHOT);
        }
        if (!isUsableSnapshot(subject, snapshot)) {
            return denied(ProjectCaseAccessReason.PUBLISHED_SNAPSHOT_UNAVAILABLE,
                    ProjectCaseAccessReason.PUBLISHED_SNAPSHOT_UNAVAILABLE);
        }

        boolean canView = YES.equals(subject.getAllowPreview());
        ProjectCaseAccessReason viewReason = canView ? null
                : ProjectCaseAccessReason.PROJECT_CONTENT_SHARING_DISABLED;
        if (!MOVIE.equals(subject.getProjectType())) {
            return summary(canView, viewReason, false, ProjectCaseAccessReason.PROJECT_TYPE_UNSUPPORTED);
        }
        if (!canView) {
            return summary(false, viewReason, false, ProjectCaseAccessReason.PROJECT_CONTENT_NOT_VIEWABLE);
        }
        if (!YES.equals(subject.getAllowCopy())) {
            return summary(true, null, false, ProjectCaseAccessReason.PROJECT_COPY_DISABLED);
        }
        return summary(true, null, true, null);
    }

    private boolean isAvailable(ProjectCaseAccessSubject subject)
    {
        return YES.equals(subject.getIsPublic())
                && NORMAL.equals(subject.getDelFlag())
                && (Objects.equals(subject.getStatus(), AUDITING)
                    || Objects.equals(subject.getStatus(), APPROVED));
    }

    private boolean isUsableSnapshot(ProjectCaseAccessSubject subject, AidProjectPublishSnapshot snapshot)
    {
        return snapshot != null
                && Objects.equals(snapshot.getProjectId(), subject.getProjectId())
                && Objects.equals(snapshot.getSchemaVersion(), SNAPSHOT_SCHEMA_VERSION)
                && StrUtil.length(snapshot.getContentHash()) == 64
                && snapshot.getNodeCount() != null
                && snapshot.getNodeCount() >= 0
                && snapshot.getNodeCount() <= MAX_NODE_COUNT;
    }

    private ProjectCaseAccessSummary unavailable()
    {
        return denied(ProjectCaseAccessReason.PROJECT_UNAVAILABLE,
                ProjectCaseAccessReason.PROJECT_UNAVAILABLE);
    }

    private ProjectCaseAccessSummary denied(ProjectCaseAccessReason viewReason,
                                            ProjectCaseAccessReason copyReason)
    {
        return summary(false, viewReason, false, copyReason);
    }

    private ProjectCaseAccessSummary summary(boolean canView, ProjectCaseAccessReason viewReason,
                                             boolean canCopy, ProjectCaseAccessReason copyReason)
    {
        return ProjectCaseAccessSummary.builder()
                .canViewProjectContent(canView)
                .viewProjectContentReasonCode(viewReason == null ? null : viewReason.name())
                .canCopyProject(canCopy)
                .copyProjectReasonCode(copyReason == null ? null : copyReason.name())
                .copyRequiresLogin(true)
                .build();
    }
}
