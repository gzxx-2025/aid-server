package com.aid.projectgenconfig.controller;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.projectgenconfig.dto.ProjectGenConfigQueryRequest;
import com.aid.projectgenconfig.dto.SaveProjectGenConfigRequest;
import com.aid.projectgenconfig.service.IProjectGenConfigService;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目级生成配置 Controller（C端），按业务场景保存/查询项目的智能体与模型（含图片清晰度/比例）配置。
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/api/user/project/gen-config")
public class ProjectGenConfigController extends BaseController
{
    @Resource
    private IProjectGenConfigService projectGenConfigService;

    /**
     * 保存项目级生成配置（部分更新：仅保存本次传入的场景，未传入的场景保持不变）。
     * 入参 {@link SaveProjectGenConfigRequest}：projectId + configs（场景配置列表），
     * 每个场景项含 sceneCode、agentCode、modelCode，图片类场景含 resolution，分镜生图含 aspectRatio；
     * 任一场景校验失败整批回滚。出参 data 为本次已保存的场景配置列表。
     */
    @PostMapping("/save")
    public AjaxResult save(@Valid @RequestBody SaveProjectGenConfigRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try
        {
            return AjaxResult.success("保存成功", projectGenConfigService.saveConfig(request, userId));
        }
        catch (RuntimeException e)
        {
            logger.error(e.getMessage());
            return error(e.getMessage());
        }
    }

    /**
     * 查询项目级生成配置。
     * 入参 {@link ProjectGenConfigQueryRequest}：projectId + 可选 episodeId。
     * 逐场景解析：优先取项目已保存配置（source=project），否则回退智能体矩阵默认（source=default），
     * 均无则 source=none。出参 data 为各场景配置列表（含可选智能体下拉与可选模型池）。
     */
    @PostMapping("/get")
    public AjaxResult get(@Valid @RequestBody ProjectGenConfigQueryRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try
        {
            return AjaxResult.success(projectGenConfigService.listConfig(request.getProjectId(), request.getEpisodeId(), userId));
        }
        catch (RuntimeException e)
        {
            logger.error(e.getMessage());
            return error(e.getMessage());
        }
    }
}
