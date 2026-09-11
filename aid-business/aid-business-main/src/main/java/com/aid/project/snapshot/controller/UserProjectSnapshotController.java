package com.aid.project.snapshot.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.project.snapshot.dto.ProjectSnapshotCopyRequest;
import com.aid.project.snapshot.service.IProjectSnapshotService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 登录用户从发布快照复制电影工程。 */
@RestController
@RequestMapping("/api/user/project")
@RequiredArgsConstructor
@Tag(name = "项目发布流程", description = "从审核通过的电影流程快照创建独立工程")
public class UserProjectSnapshotController
{
    private final IProjectSnapshotService projectSnapshotService;

    @PostMapping("/copy")
    @Operation(summary = "复制电影工程",
            description = "只复制流程和预览时间线，不复制最终成片；requestId 用于安全重试和去重")
    public AjaxResult copy(@Valid @RequestBody ProjectSnapshotCopyRequest request)
    {
        return AjaxResult.success("复制成功", projectSnapshotService.copy(
                request, SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }
}
