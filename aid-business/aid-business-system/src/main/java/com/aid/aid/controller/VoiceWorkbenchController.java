package com.aid.aid.controller;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.voice.dto.VoiceWorkbenchPublishRequest;
import com.aid.voice.dto.VoiceWorkbenchRequest;
import com.aid.voice.service.VoiceWorkbenchService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 后台音色制作入口，不对网站用户账户执行扣款。 */
@RestController
@RequestMapping("/aid/voice-workbench")
@RequiredArgsConstructor
public class VoiceWorkbenchController {
    private final VoiceWorkbenchService workbench;

    @GetMapping("/capabilities")
    @PreAuthorize("@ss.hasPermi('aid:voice-library:query')")
    @Operation(summary = "查询模型音色制作能力与限制")
    public AjaxResult capabilities(@RequestParam Long modelId) { return AjaxResult.success(workbench.capabilities(modelId)); }

    @PostMapping("/quote")
    @PreAuthorize("@ss.hasPermi('aid:voice-library:add')")
    @Operation(summary = "报价并签发绑定管理员与模型配置的报价引用")
    public AjaxResult quote(@Valid @RequestBody VoiceWorkbenchRequest request) {
        return AjaxResult.success(workbench.quote(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/create")
    @PreAuthorize("@ss.hasPermi('aid:voice-library:add')")
    @Operation(summary = "确认上游费用后创建统一音色任务")
    public AjaxResult create(@Valid @RequestBody VoiceWorkbenchRequest request) {
        return AjaxResult.success(workbench.create(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/samples")
    @PreAuthorize("@ss.hasPermi('aid:voice-library:add')")
    @Operation(summary = "验证并上传已获授权的克隆样本")
    public AjaxResult samples(@RequestParam Long modelId, @RequestParam boolean rightsConfirmed, @RequestParam MultipartFile file) {
        return AjaxResult.success(workbench.upload(modelId, file, rightsConfirmed, SecurityUtils.getUserId()));
    }

    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("@ss.hasPermi('aid:voice-library:query')")
    @Operation(summary = "只读查询本人后台音色任务，不触发上游轮询或重建")
    public AjaxResult task(@PathVariable Long taskId) { return AjaxResult.success(workbench.task(taskId, SecurityUtils.getUserId())); }

    @PostMapping("/publish")
    @PreAuthorize("@ss.hasPermi('aid:voice-library:add') and @ss.hasPermi('aid:voice-library:edit')")
    @Operation(summary = "将已持久化制作结果保存为草稿或发布到音色库")
    public AjaxResult publish(@Valid @RequestBody VoiceWorkbenchPublishRequest request) {
        return AjaxResult.success(workbench.publish(request, SecurityUtils.getUserId()));
    }
}
