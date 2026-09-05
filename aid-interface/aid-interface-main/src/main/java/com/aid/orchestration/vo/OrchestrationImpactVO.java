package com.aid.orchestration.vo;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 模型或智能体下线前的影响预览。
 */
@Data
@Builder
public class OrchestrationImpactVO
{
    private String resourceType;
    private Long resourceId;
    private String resourceCode;
    private String resourceName;
    private String bizCategoryCode;
    private long activeReferenceCount;
    private boolean canRetireDirectly;
    private boolean replacementSupported;
    private List<OrchestrationImpactItemVO> references;
    private String historyPolicy;
}
