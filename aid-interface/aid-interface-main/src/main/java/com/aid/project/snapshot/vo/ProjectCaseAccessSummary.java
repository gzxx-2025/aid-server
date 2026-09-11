package com.aid.project.snapshot.vo;

import lombok.Builder;
import lombok.Data;

/** 案例广场项目当前有效访问能力摘要。 */
@Data
@Builder
public class ProjectCaseAccessSummary
{
    private Boolean canViewProjectContent;
    private String viewProjectContentReasonCode;
    private Boolean canCopyProject;
    private String copyProjectReasonCode;
    private Boolean copyRequiresLogin;
}
