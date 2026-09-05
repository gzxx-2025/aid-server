package com.aid.orchestration.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 编排资源的一类活动引用影响。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrchestrationImpactItemVO
{
    /** 引用类型：model_pool / agent / matrix / project_config / voice。 */
    private String type;

    /** 管理端展示名称。 */
    private String label;

    /** 活动引用数量。 */
    private long count;

    /** 下线时采用的处理方式。 */
    private String action;
}
