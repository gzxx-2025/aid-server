package com.aid.aid.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.agent.IAidAgentService;
import com.aid.agent.dto.AgentQueryRequest;
import com.aid.aid.domain.AidAgent;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.enums.BusinessType;

import lombok.extern.slf4j.Slf4j;

/**
 * 智能体配置 后台管理 Controller
 * 后台 REST 接口：分页查询、详情、新增、修改、删除。
 * 管理端可见 prompt_content 字段；C 端禁止暴露，参见 {@link com.aid.aid.controller.AgentClientController}.
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aid/agent")
public class AidAgentController extends BaseController
{
    @Autowired
    private IAidAgentService aidAgentService;

    /**
     * 查询智能体列表（分页 + 条件，支持 bizType 过滤）
     */
    @PreAuthorize("@ss.hasPermi('aid:agent:list')")
    @GetMapping("/list")
    public TableDataInfo list(AgentQueryRequest query)
    {
        startPage();
        List<AidAgent> list = aidAgentService.listAgents(query);
        return getDataTable(list);
    }

    /**
     * 按 bizCategoryCode 查询启用的智能体（不分页，供后台下拉使用）。
     * 走 GET，返回含 {@code promptContent} 的 {@link AidAgent}，不经过 C 端加密链路；
     * 未启用 / 已删除的智能体自动剔除。
     *
     * @param bizCategoryCode 业务分类编码（= sceneCode）
     * @return 该场景下启用的智能体列表
     */
    @PreAuthorize("@ss.hasPermi('aid:agent:list')")
    @GetMapping("/listByBizCategory")
    public AjaxResult listByBizCategory(String bizCategoryCode)
    {
        AgentQueryRequest q = new AgentQueryRequest();
        q.setBizCategoryCode(bizCategoryCode);
        q.setStatus(1); // 仅启用（1=启用 0=停用）
        List<AidAgent> list = aidAgentService.listAgents(q);
        return success(list);
    }

    /**
     * 获取智能体详细信息（管理端可见 promptContent）
     */
    @PreAuthorize("@ss.hasPermi('aid:agent:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidAgentService.getById(id));
    }

    /**
     * 新增智能体
     */
    @PreAuthorize("@ss.hasPermi('aid:agent:add')")
    @Log(title = "智能体配置", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Valid @RequestBody AidAgent agent)
    {
        try
        {
            return toAjax(aidAgentService.insertAgent(agent));
        }
        catch (RuntimeException e)
        {
            log.error("新增智能体失败: {}", e.getMessage(), e);
            return error(e.getMessage());
        }
    }

    /**
     * 修改智能体
     */
    @PreAuthorize("@ss.hasPermi('aid:agent:edit')")
    @Log(title = "智能体配置", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Valid @RequestBody AidAgent agent)
    {
        try
        {
            return toAjax(aidAgentService.updateAgent(agent));
        }
        catch (RuntimeException e)
        {
            log.error("修改智能体失败: {}", e.getMessage(), e);
            return error(e.getMessage());
        }
    }

    /**
     * 删除智能体
     */
    @PreAuthorize("@ss.hasPermi('aid:agent:remove')")
    @Log(title = "智能体配置", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable("id") Long id)
    {
        try
        {
            return toAjax(aidAgentService.deleteAgentById(id));
        }
        catch (RuntimeException e)
        {
            log.error("删除智能体失败: {}", e.getMessage(), e);
            return error(e.getMessage());
        }
    }
}
