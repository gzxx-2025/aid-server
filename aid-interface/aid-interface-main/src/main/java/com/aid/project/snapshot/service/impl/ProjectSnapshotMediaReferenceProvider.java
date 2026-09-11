package com.aid.project.snapshot.service.impl;

import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.aid.aid.domain.AidComicProject;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.media.cleanup.IAdditionalMediaReferenceProvider;
import com.aid.project.snapshot.domain.AidProjectPublishSnapshotMedia;
import com.aid.project.snapshot.mapper.AidProjectPublishSnapshotMediaMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import lombok.RequiredArgsConstructor;

/** 当前发布版本或有效复制工程引用的快照媒体参与保护。 */
@Component
@RequiredArgsConstructor
public class ProjectSnapshotMediaReferenceProvider implements IAdditionalMediaReferenceProvider
{
    private final AidProjectPublishSnapshotMediaMapper mediaMapper;
    private final IAidComicProjectService projectService;

    @Override
    public Set<String> findReferenced(Collection<String> candidates)
    {
        if (candidates == null || candidates.isEmpty()) {
            return Set.of();
        }
        List<AidProjectPublishSnapshotMedia> references = mediaMapper.selectList(
                Wrappers.<AidProjectPublishSnapshotMedia>lambdaQuery()
                        .select(AidProjectPublishSnapshotMedia::getSnapshotId,
                                AidProjectPublishSnapshotMedia::getMediaUrl)
                        .in(AidProjectPublishSnapshotMedia::getMediaUrl, candidates));
        Set<Long> snapshotIds = new LinkedHashSet<>();
        references.forEach(row -> snapshotIds.add(row.getSnapshotId()));
        snapshotIds.remove(null);
        if (snapshotIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> activeSnapshotIds = new LinkedHashSet<>();
        projectService.list(Wrappers.<AidComicProject>lambdaQuery()
                .select(AidComicProject::getPublishedSnapshotId, AidComicProject::getSourceSnapshotId)
                .eq(AidComicProject::getDelFlag, "0")
                .and(wrapper -> wrapper.in(AidComicProject::getPublishedSnapshotId, snapshotIds)
                        .or().in(AidComicProject::getSourceSnapshotId, snapshotIds)))
                .forEach(row -> {
                    activeSnapshotIds.add(row.getPublishedSnapshotId());
                    activeSnapshotIds.add(row.getSourceSnapshotId());
                });
        activeSnapshotIds.remove(null);
        Set<String> result = new LinkedHashSet<>();
        references.stream()
                .filter(row -> activeSnapshotIds.contains(row.getSnapshotId()))
                .map(AidProjectPublishSnapshotMedia::getMediaUrl)
                .forEach(result::add);
        return result;
    }
}
