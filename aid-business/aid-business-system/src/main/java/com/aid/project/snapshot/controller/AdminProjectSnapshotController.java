package com.aid.project.snapshot.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.annotation.Log;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.SecurityUtils;
import com.aid.project.snapshot.dto.AdminProjectCopyLabelRequest;
import com.aid.project.snapshot.dto.AdminProjectSnapshotRebuildRequest;
import com.aid.project.snapshot.service.IProjectSnapshotService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 后台管理复制来源标签和已发布快照。 */
@RestController
@RequestMapping("/aid/project-copy")
@RequiredArgsConstructor
@Tag(name = "项目复制管理")
public class AdminProjectSnapshotController
{
    private final IProjectSnapshotService projectSnapshotService;

    @PreAuthorize("@ss.hasPermi('aid:publish:edit')")
    @Log(title = "项目复制标签", businessType = BusinessType.UPDATE)
    @PostMapping("/label")
    @Operation(summary = "修改复制项目标签", description = "仅复制来源项目可设置；空值恢复默认标签")
    public AjaxResult updateLabel(@Valid @RequestBody AdminProjectCopyLabelRequest request)
    {
        projectSnapshotService.updateCopyLabel(request, SecurityUtils.getUsername());
        return AjaxResult.success("修改成功");
    }

    @PreAuthorize("@ss.hasPermi('aid:publish:edit')")
    @Log(title = "重建项目发布快照", businessType = BusinessType.UPDATE)
    @PostMapping("/snapshot/rebuild")
    @Operation(summary = "重建发布快照", description = "仅在已发布项目的审核内容未变化时生成新 revision，相同内容不重复生成")
    public AjaxResult rebuildSnapshot(@Valid @RequestBody AdminProjectSnapshotRebuildRequest request)
    {
        projectSnapshotService.rebuildApprovedSnapshot(request, SecurityUtils.getUsername());
        return AjaxResult.success("快照重建成功");
    }
}
