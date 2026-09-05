package com.aid.aid.controller;

import com.aid.common.annotation.Log;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.upgrade.dto.NginxConfigRequest;
import com.aid.upgrade.service.NginxManagementService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 后台受管Nginx配置入口。 */
@RestController
@RequestMapping("/aidconfig/upgrade/nginx")
@RequiredArgsConstructor
public class NginxManagementController {
    private final NginxManagementService nginx;

    @GetMapping
    @PreAuthorize("@ss.hasPermi('aidconfig:upgrade:list')")
    @Operation(summary = "读取受管Nginx配置", description = "返回配置快照、修订摘要及当前配置预览，不返回凭证。")
    public AjaxResult configuration() { return AjaxResult.success(nginx.configuration()); }

    @PostMapping("/validate")
    @PreAuthorize("@ss.hasPermi('aidconfig:upgrade:nginx')")
    @Log(title = "Nginx配置校验", businessType = BusinessType.OTHER, isSaveRequestData = false)
    @Operation(summary = "校验Nginx候选配置", description = "只生成临时配置并校验，不改变生效配置；返回任务ID。")
    public AjaxResult validate(@Valid @RequestBody NginxConfigRequest request) {
        return AjaxResult.success("校验任务已受理", nginx.submit("NGINX_VALIDATE", request));
    }

    @PostMapping("/apply")
    @PreAuthorize("@ss.hasPermi('aidconfig:upgrade:nginx')")
    @Log(title = "Nginx配置应用", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @Operation(summary = "应用并平滑重载Nginx", description = "校验修订摘要，备份后写入受管配置；失败自动恢复，不重启应用。")
    public AjaxResult apply(@Valid @RequestBody NginxConfigRequest request) {
        return AjaxResult.success("应用任务已受理", nginx.submit("NGINX_APPLY", request));
    }

    @PostMapping("/rollback")
    @PreAuthorize("@ss.hasPermi('aidconfig:upgrade:nginx')")
    @Log(title = "Nginx配置恢复", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @Operation(summary = "恢复上次Nginx配置", description = "仅恢复Nginx字段，不回退数据库等其他部署配置；返回任务ID。")
    public AjaxResult rollback(@Valid @RequestBody NginxConfigRequest request) {
        return AjaxResult.success("恢复任务已受理", nginx.submit("NGINX_ROLLBACK", request));
    }
}
