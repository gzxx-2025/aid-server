package com.aid.project.snapshot.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectSnapshotCopyResultVO
{
    private Long projectId;
    private Long sourceProjectId;
    private Long sourceSnapshotId;
    private String copyLabel;
    private Boolean reused;
}
