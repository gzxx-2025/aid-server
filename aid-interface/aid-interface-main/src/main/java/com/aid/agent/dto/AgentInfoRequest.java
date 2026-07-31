package com.aid.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * C 端：根据智能体编码查询智能体信息请求 DTO
 *
 * @author 视觉AID
 */
@Data
public class AgentInfoRequest
{
    /** 智能体编码 */
    @NotBlank(message = "智能体编码不能为空")
    private String agentCode;
}
