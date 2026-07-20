package com.aid.agent.vo;

import lombok.Data;

/**
 * C 端智能体信息 VO
 * 显式不包含 prompt_content 字段；C 端任何接口都不会暴露提示词正文。
 *
 * @author 视觉AID
 */
@Data
public class AgentInfoVO
{
    /** 主键ID */
    private Long id;

    /** 智能体编码 */
    private String agentCode;

    /** 智能体名称 */
    private String name;

    /** 智能体图标地址 */
    private String iconUrl;

    /** 副标题/简述 */
    private String subTitle;

    /** 介绍 */
    private String introduction;

    /** 默认模型编码 */
    private String modelCode;

    /** 业务分类编码（与 aid_ai_model_func_config.func_code 联动） */
    private String bizCategoryCode;

    /** 状态：1启用 0停用 */
    private Integer status;
}
