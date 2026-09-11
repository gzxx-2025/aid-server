package com.aid.project.snapshot.model;

import lombok.Builder;
import lombok.Data;

/** 案例广场项目访问判定所需的最小字段。 */
@Data
@Builder
public class ProjectCaseAccessSubject
{
    private Long projectId;
    private String projectType;
    private String isPublic;
    private String delFlag;
    private Integer status;
    private String allowPreview;
    private String allowCopy;
    private Long publishedSnapshotId;
}
