package com.aid.agent.controller;

import java.util.List;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.agent.IAidAgentService;
import com.aid.agent.dto.AgentInfoRequest;
import com.aid.agent.dto.AgentListRequest;
import com.aid.agent.vo.AgentListGroupVO;
import com.aid.aid.domain.AidAgent;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 智能体 C 端 Controller。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aid/agent")
public class AgentClientController extends BaseController
{
    @Resource
    private IAidAgentService aidAgentService;

    /**
     * 根据 agent_code 查询智能体信息（不含 prompt_content）。
     */
    @PostMapping("/info")
    public AjaxResult info(@Valid @RequestBody AgentInfoRequest request)
    {
        try
        {
            AidAgent agent = aidAgentService.getByAgentCode(request.getAgentCode());
            if (agent == null)
            {
                log.error("智能体查询: 未找到, agentCode={}", request.getAgentCode());
                return error("智能体不存在");
            }
            // 通过 toClientVO 屏蔽 prompt_content
            return success(aidAgentService.toClientVO(agent));
        }
        catch (RuntimeException e)
        {
            log.error("智能体查询失败: {}", e.getMessage(), e);
            return error(e.getMessage());
        }
    }

    /**
     * 按 bizCategoryCodes 查询启用智能体列表，按业务分类分组返回（不含 prompt_content）。
     * 入参 {@code bizCategoryCodes} 为可选过滤条件：
     *
     *   - 不传 / 空数组 → 返回全部启用智能体，按各自的 {@code bizCategoryCode} 分组（{@code bizCategoryCode} 为空的归入 {@code null} 分组）
     *   - 传了 → 仅返回这些分类下的启用智能体，按入参顺序输出每个分组；某个分类无匹配数据时该分组的 {@code agents} 为空数组（占位）
     *
     */
    @PostMapping("/list")
    public AjaxResult list(@RequestBody(required = false) AgentListRequest request)
    {
        try
        {
            List<String> bizCategoryCodes = request == null ? null : request.getBizCategoryCodes();
            Long projectId = request == null ? null : request.getProjectId();
            Long episodeId = request == null ? null : request.getEpisodeId();
            Long userId = SecurityUtils.getUserId();
            // 传了 projectId 则按该项目创作模式裁剪受矩阵管理场景的候选智能体（防列出不适用智能体）
            List<AgentListGroupVO> data = aidAgentService.listEnabledAgentsGroupedForClient(
                    bizCategoryCodes, projectId, episodeId, userId);
            return success(data);
        }
        catch (RuntimeException e)
        {
            log.error("智能体列表查询失败: {}", e.getMessage(), e);
            return error(e.getMessage());
        }
    }
}
