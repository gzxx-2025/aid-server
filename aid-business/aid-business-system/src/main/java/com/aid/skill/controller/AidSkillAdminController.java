package com.aid.skill.controller;

import com.aid.common.annotation.Log;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.SecurityUtils;
import com.aid.skill.dto.SkillAdminRequests;
import com.aid.skill.dto.SkillPackageAdminRequests;
import com.aid.skill.service.ISkillAdminService;
import com.aid.skill.service.ISkillPackageAdminService;
import com.aid.skill.vo.SkillAdminVO;
import com.aid.skill.vo.SkillPackageAdminVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Skill 配置与运行审计接口。 */
@RestController
@RequestMapping("/aid/skill")
@RequiredArgsConstructor
@Tag(name = "Skill 管理")
public class AidSkillAdminController {

    private final ISkillAdminService skillAdminService;
    private final ISkillPackageAdminService skillPackageAdminService;

    @PreAuthorize("@ss.hasPermi('aid:skill:list')")
    @PostMapping("/list")
    @Operation(summary = "分页查询 Skill 摘要")
    public AjaxResult list(@Valid @RequestBody(required = false) SkillAdminRequests.PageRequest request) {
        return page(skillAdminService.pageSkills(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:query') and @ss.hasPermi('aid:skill:edit')")
    @Log(title = "Skill 稳定身份", businessType = BusinessType.UPDATE)
    @PostMapping("/identity/edit")
    @Operation(summary = "保存 Skill 稳定身份",
            description = "只更新名称、说明、图标和总开关，不传输或修改版本包内容")
    public AjaxResult editIdentity(@Valid @RequestBody SkillAdminRequests.IdentitySaveRequest request) {
        skillAdminService.updateIdentity(request, SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:edit')")
    @Log(title = "Skill 状态", businessType = BusinessType.UPDATE)
    @PostMapping("/status")
    @Operation(summary = "启用或停用 Skill")
    public AjaxResult status(@Valid @RequestBody SkillAdminRequests.StatusRequest request) {
        skillAdminService.updateStatus(request, SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:remove')")
    @Log(title = "Skill", businessType = BusinessType.DELETE)
    @PostMapping("/remove")
    @Operation(summary = "软删除 Skill")
    public AjaxResult remove(@Valid @RequestBody SkillAdminRequests.DetailRequest request) {
        skillAdminService.deleteSkill(request.getId(), SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:restore')")
    @Log(title = "Skill 恢复", businessType = BusinessType.UPDATE)
    @PostMapping("/restore")
    @Operation(summary = "恢复已删除 Skill", description = "恢复后固定为停用状态，需人工确认配置后再启用")
    public AjaxResult restore(@Valid @RequestBody SkillAdminRequests.DetailRequest request) {
        skillAdminService.restoreSkill(request.getId(), SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:query')")
    @PostMapping("/model/options")
    @Operation(summary = "查询可选文本模型",
            description = "返回模型编码、名称、Logo、服务商、启停状态、结构化能力和价格；停用旧引用仍可识别但禁止新选")
    public AjaxResult modelOptions(@RequestBody(required = false) SkillAdminRequests.EmptyRequest request) {
        return AjaxResult.success(skillAdminService.listTextModelOptions());
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:query')")
    @PostMapping("/version/list")
    @Operation(summary = "查询 Skill 不可变版本列表")
    public AjaxResult versions(@Valid @RequestBody SkillPackageAdminRequests.VersionPageRequest request) {
        SkillPackageAdminVO.VersionPageResult versionPage = skillPackageAdminService.listVersions(request);
        AjaxResult result = page(versionPage);
        result.put("currentVersionId", versionPage.getCurrentVersionId());
        return result;
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:query')")
    @PostMapping("/version/detail")
    @Operation(summary = "查询 Skill 不可变版本详情")
    public AjaxResult versionDetail(@Valid @RequestBody SkillPackageAdminRequests.VersionRequest request) {
        return AjaxResult.success(skillPackageAdminService.getVersion(request.getId()));
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:query')")
    @PostMapping("/dependency/options")
    @Operation(summary = "分页查询可固定的子 Skill")
    public AjaxResult dependencyOptions(
            @Valid @RequestBody SkillPackageAdminRequests.DependencySkillPageRequest request) {
        return page(skillPackageAdminService.listDependencyOptions(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:query')")
    @PostMapping("/dependency/version/options")
    @Operation(summary = "分页查询可固定的子 Skill 版本")
    public AjaxResult dependencyVersionOptions(
            @Valid @RequestBody SkillPackageAdminRequests.DependencyVersionPageRequest request) {
        return page(skillPackageAdminService.listDependencyVersionOptions(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:query')")
    @PostMapping("/dependency/labels")
    @Operation(summary = "批量查询已选子 Skill 版本标签")
    public AjaxResult dependencyLabels(
            @Valid @RequestBody SkillPackageAdminRequests.DependencyLabelRequest request) {
        return AjaxResult.success(skillPackageAdminService.listDependencyLabels(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:query')")
    @PostMapping("/draft/detail")
    @Operation(summary = "查询当前管理员的 Skill 草稿",
            description = "没有草稿时按当前版本返回只读种子；尚无当前版本时按稳定身份起草，不产生数据库写入")
    public AjaxResult draftDetail(@Valid @RequestBody SkillPackageAdminRequests.DraftRequest request) {
        return AjaxResult.success(skillPackageAdminService.getDraft(
                request.getSkillId(), request.getBaseVersionId(), SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:query') and @ss.hasPermi('aid:skill:edit')")
    @Log(title = "Skill 草稿", businessType = BusinessType.UPDATE,
            isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/draft/save")
    @Operation(summary = "保存 Skill 草稿", description = "仅保存草稿，不改变当前运行版本")
    public AjaxResult saveDraft(@Valid @RequestBody SkillPackageAdminRequests.DraftSaveRequest request) {
        return AjaxResult.success(skillPackageAdminService.saveDraft(
                request, SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:query') and @ss.hasPermi('aid:skill:edit')")
    @PostMapping("/draft/validate")
    @Operation(summary = "校验 Skill 草稿", description = "只读校验，不保存、不发布")
    public AjaxResult validateDraft(@Valid @RequestBody SkillPackageAdminRequests.DraftValidateRequest request) {
        return AjaxResult.success(skillPackageAdminService.validateDraft(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:query') and @ss.hasPermi('aid:skill:edit')")
    @Log(title = "Skill 草稿放弃", businessType = BusinessType.UPDATE,
            isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/draft/discard")
    @Operation(summary = "放弃 Skill 草稿", description = "释放当前活动草稿，可重新从任意历史版本起草")
    public AjaxResult discardDraft(@Valid @RequestBody SkillPackageAdminRequests.DraftDiscardRequest request) {
        skillPackageAdminService.discard(request, SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:query') and @ss.hasPermi('aid:skill:edit')")
    @Log(title = "Skill 版本发布", businessType = BusinessType.INSERT,
            isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/draft/publish")
    @Operation(summary = "发布 Skill 不可变版本",
            description = "发布后不自动切换当前版本，避免草稿误操作影响运行")
    public AjaxResult publishDraft(@Valid @RequestBody SkillPackageAdminRequests.DraftPublishRequest request) {
        return AjaxResult.success(skillPackageAdminService.publish(
                request, SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:query') and @ss.hasPermi('aid:skill:edit')")
    @Log(title = "Skill 版本切换", businessType = BusinessType.UPDATE)
    @PostMapping("/version/activate")
    @Operation(summary = "切换 Skill 当前运行版本")
    public AjaxResult activateVersion(
            @Valid @RequestBody SkillPackageAdminRequests.VersionActivateRequest request) {
        skillPackageAdminService.activate(request, SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:run:list')")
    @PostMapping("/run/list")
    @Operation(summary = "分页查询 Skill Run 审计摘要")
    public AjaxResult runs(@Valid @RequestBody(required = false) SkillAdminRequests.RunPageRequest request) {
        return page(skillAdminService.pageRuns(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:skill:run:query')")
    @PostMapping("/run/detail")
    @Operation(summary = "查询 Skill Run 详情")
    public AjaxResult runDetail(@Valid @RequestBody SkillAdminRequests.DetailRequest request) {
        return AjaxResult.success(skillAdminService.getRun(request.getId()));
    }

    private static AjaxResult page(SkillAdminVO.PageResult<?> page) {
        AjaxResult result = AjaxResult.success();
        result.put("total", page.getTotal());
        result.put("data", page.getData());
        return result;
    }
}
