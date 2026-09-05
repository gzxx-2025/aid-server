package com.aid.aid.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.SecurityUtils;
import com.aid.orchestration.IAiOrchestrationService;
import com.aid.orchestration.dto.RetireResourceRequest;

import jakarta.validation.Valid;

/**
 * AI 业务编排影响预览与受控下线接口。
 */
@RestController
@RequestMapping("/aid/orchestration")
public class AidAiOrchestrationController extends BaseController
{
    @Autowired
    private IAiOrchestrationService orchestrationService;

    @PreAuthorize("@ss.hasPermi('aid:aidmodel:remove')")
    @GetMapping("/models/{id}/impact")
    public AjaxResult previewModel(@PathVariable("id") Long id)
    {
        return success(orchestrationService.previewModelRetirement(id));
    }

    @PreAuthorize("@ss.hasPermi('aid:aidmodel:remove')")
    @Log(title = "AI业务编排-模型受控下线", businessType = BusinessType.DELETE)
    @PostMapping("/models/{id}/retire")
    public AjaxResult retireModel(@PathVariable("id") Long id,
            @Valid @RequestBody RetireResourceRequest request)
    {
        orchestrationService.retireModel(id, request, SecurityUtils.getUsername());
        return success("模型已下线，活动引用已处理，历史记录保持不变");
    }

    @PreAuthorize("@ss.hasPermi('aid:agent:remove')")
    @GetMapping("/agents/{id}/impact")
    public AjaxResult previewAgent(@PathVariable("id") Long id)
    {
        return success(orchestrationService.previewAgentRetirement(id));
    }

    @PreAuthorize("@ss.hasPermi('aid:agent:remove')")
    @Log(title = "AI业务编排-智能体受控下线", businessType = BusinessType.DELETE)
    @PostMapping("/agents/{id}/retire")
    public AjaxResult retireAgent(@PathVariable("id") Long id,
            @Valid @RequestBody RetireResourceRequest request)
    {
        orchestrationService.retireAgent(id, request, SecurityUtils.getUsername());
        return success("智能体已下线，活动引用已处理，历史记录保持不变");
    }
}
