package com.aid.project.snapshot.service;

import com.aid.project.snapshot.dto.AdminProjectCopyLabelRequest;
import com.aid.project.snapshot.dto.AdminProjectSnapshotRebuildRequest;
import com.aid.project.snapshot.dto.ProjectSnapshotCopyRequest;
import com.aid.project.snapshot.vo.ProjectSnapshotCopyResultVO;
import com.aid.project.snapshot.vo.ProjectSnapshotPreviewVO;

public interface IProjectSnapshotService
{
    void captureApprovedSnapshot(Long projectId, Long ownerUserId, String operator);

    ProjectSnapshotPreviewVO preview(Long projectId);

    ProjectSnapshotCopyResultVO copy(ProjectSnapshotCopyRequest request, Long userId, String operator);

    void updateCopyLabel(AdminProjectCopyLabelRequest request, String operator);

    void rebuildApprovedSnapshot(AdminProjectSnapshotRebuildRequest request, String operator);
}
