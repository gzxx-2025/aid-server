package com.aid.agent.dto;

import lombok.Data;

/**
 * 后台：智能体列表查询条件 DTO
 *
 * @author 视觉AID
 */
@Data
public class AgentQueryRequest
{
    /** 业务分类编码 */
    private String bizCategoryCode;

    /** 名称模糊匹配 */
    private String name;

    /** 智能体编码 */
    private String agentCode;

    /** 状态 */
    private Integer status;
}
