package com.aid.model.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.model.dto.AiModelByFuncRequest;
import com.aid.model.dto.AiModelListRequest;
import com.aid.model.service.IAiModelBusinessService;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

/**
 * C端AI模型Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/api/user/model")
public class AiModelController extends BaseController
{
    @Resource
    private IAiModelBusinessService aiModelBusinessService;

    /**
     * 查询可用AI模型列表。
     */
    @PostMapping("/list")
    public AjaxResult list(@RequestBody AiModelListRequest request)
    {
        return success(aiModelBusinessService.listAvailableModels(request));
    }

    /**
     * 按功能编码批量查询可用AI模型列表（按功能分组返回）。
     * 可选传入 projectId/episodeId：专业版多参出片自动切到 main_storyboard_video_multi_pro。
     */
    @PostMapping("/listByFunc")
    public AjaxResult listByFunc(@Valid @RequestBody AiModelByFuncRequest request)
    {
        return success(aiModelBusinessService.listAvailableModelsGroupedByFuncCodes(
                request.resolveFuncCodes(),
                request.getProjectId(),
                request.getEpisodeId(),
                SecurityUtils.getUserId()));
    }
}
