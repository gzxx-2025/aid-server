package com.aid.project.snapshot.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.annotation.Anonymous;
import com.aid.common.core.domain.AjaxResult;
import com.aid.project.snapshot.dto.ProjectSnapshotPreviewRequest;
import com.aid.project.snapshot.service.IProjectSnapshotService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 已发布项目流程的匿名只读预览接口。 */
@RestController
@RequestMapping("/api/public/project")
@RequiredArgsConstructor
@Tag(name = "项目发布流程", description = "读取审核通过且作者已开放的不可变流程快照")
public class PublicProjectSnapshotController
{
    private final IProjectSnapshotService projectSnapshotService;

    @Anonymous
    @PostMapping("/preview")
    @Operation(summary = "预览项目发布流程",
            description = "返回当前审核通过版本的可读节点和连线；作者后续编辑草稿不会改变本响应")
    public AjaxResult preview(@Valid @RequestBody ProjectSnapshotPreviewRequest request)
    {
        return AjaxResult.success(projectSnapshotService.preview(request.getId()));
    }
}
