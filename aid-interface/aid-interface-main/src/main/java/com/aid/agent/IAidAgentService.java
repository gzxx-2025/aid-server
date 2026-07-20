package com.aid.agent;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.agent.dto.AgentQueryRequest;
import com.aid.agent.vo.AgentInfoVO;
import com.aid.agent.vo.AgentListGroupVO;
import com.aid.aid.domain.AidAgent;

/**
 * 智能体业务 Service 接口
 * 提供智能体的查询、按编码取用、按业务分类断言、CRUD 等能力。
 * 注意：C 端调用必须通过返回 {@link AgentInfoVO} 的方法，避免泄露 prompt_content。
 *
 * @author 视觉AID
 */
public interface IAidAgentService extends IService<AidAgent>
{
    /**
     * 根据 agent_code 查询智能体（含 prompt_content，仅供后端业务调用，不要直接返回 C 端）。
     *
     * @param agentCode 智能体编码
     * @return 智能体实体；不存在或停用返回 null
     */
    AidAgent getByAgentCode(String agentCode);

    /**
     * 根据 agent_code 查询智能体并断言 biz_category_code 匹配。
     *
     * @param agentCode                智能体编码
     * @param expectedBizCategoryCode  期望的业务分类编码
     * @return 智能体实体（必然非空、必然 biz_category_code 匹配且启用）
     */
    AidAgent getByAgentCodeAndAssertBizCategory(String agentCode, String expectedBizCategoryCode);

    /**
     * 后台：按条件分页查询智能体列表。
     * 调用方在 Controller 中先调用 {@code PageHelper.startPage}，本方法返回原始列表即可被分页插件包装。
     *
     * @param query 查询条件
     * @return 智能体列表
     */
    List<AidAgent> listAgents(AgentQueryRequest query);

    /**
     * C 端：按 biz_category_code 列表查询启用智能体并按分类分组（不含 prompt_content）。
     *
     * @param bizCategoryCodes 业务分类编码列表（可空、可重复，重复会去重）
     * @return 分组后的智能体 C 端 VO 列表
     */
    List<AgentListGroupVO> listEnabledAgentsGroupedForClient(List<String> bizCategoryCodes);

    /**
     * C 端：按 biz_category_code 列表查询启用智能体并按分类分组，且按指定项目的创作模式裁剪候选。
     *
     * @param bizCategoryCodes 业务分类编码列表（可空）
     * @param projectId        项目ID（可空，传了才按创作模式过滤）
     * @param episodeId        剧集ID（可空，剧集类项目用于取剧集创作模式）
     * @param userId           当前登录用户ID（剧集归属校验）
     * @return 分组后的智能体 C 端 VO 列表（受矩阵管理场景已按创作模式裁剪）
     */
    List<AgentListGroupVO> listEnabledAgentsGroupedForClient(List<String> bizCategoryCodes,
                                                             Long projectId, Long episodeId, Long userId);

    /**
     * 新增智能体。
     *
     * @param agent 智能体实体
     * @return 影响行数
     */
    int insertAgent(AidAgent agent);

    /**
     * 更新智能体。
     *
     * @param agent 智能体实体
     * @return 影响行数
     */
    int updateAgent(AidAgent agent);

    /**
     * 根据主键删除智能体。
     *
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteAgentById(Long id);

    /**
     * 将实体转为 C 端 VO（屏蔽 prompt_content）。
     *
     * @param agent 智能体实体
     * @return C 端 VO
     */
    AgentInfoVO toClientVO(AidAgent agent);
}
