package com.aid.agent.vo;

import java.util.List;

import lombok.Data;

/**
 * C 端：按业务分类分组的智能体列表 VO
 * 一项代表一个 {@code bizCategoryCode} 分组及该分组下的启用智能体列表（C 端 VO，已屏蔽 prompt_content）。
 *
 * @author 视觉AID
 */
@Data
public class AgentListGroupVO
{
    /** 业务分类编码；显式传空数组返回全部时，bizCategoryCode 为空的智能体会归入 {@code null} 分组 */
    private String bizCategoryCode;

    /** 该分组下的启用智能体列表（已按 id 升序）；无匹配时为长度 0 的列表（占位） */
    private List<AgentInfoVO> agents;
}
