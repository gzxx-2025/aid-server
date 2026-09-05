package com.aid.seo.controller;

import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.seo.model.SeoModels;
import com.aid.seo.service.SeoManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 搜索引擎优化、页面发现与提交台账管理。 */
@RestController
@RequestMapping("/aid/seo")
@RequiredArgsConstructor
public class SeoManagementController extends BaseController {
    private final SeoManagementService seoService;

    @PreAuthorize("@ss.hasPermi('aid:seo:list')")
    @GetMapping("/overview")
    public AjaxResult overview() {
        return success(seoService.overview());
    }

    @PreAuthorize("@ss.hasPermi('aid:seo:list')")
    @GetMapping("/pages")
    public AjaxResult pages(SeoModels.PageQuery query) {
        return success(seoService.page(query));
    }

    @PreAuthorize("@ss.hasPermi('aid:seo:query')")
    @GetMapping("/logs")
    public AjaxResult logs(@RequestParam(required = false) Long pageId,
                           @RequestParam(defaultValue = "50") int limit) {
        return success(seoService.logs(pageId, limit));
    }

    @PreAuthorize("@ss.hasPermi('aid:seo:edit')")
    @PostMapping("/pages")
    @Log(title = "SEO 页面", businessType = BusinessType.INSERT)
    public AjaxResult addPage(@RequestBody SeoModels.PageSave request) {
        request.setId(null);
        return success(seoService.savePage(request, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:seo:edit')")
    @PutMapping("/pages")
    @Log(title = "SEO 页面", businessType = BusinessType.UPDATE)
    public AjaxResult editPage(@RequestBody SeoModels.PageSave request) {
        return success(seoService.savePage(request, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:seo:edit')")
    @DeleteMapping("/pages/{pageId}")
    @Log(title = "SEO 页面", businessType = BusinessType.DELETE)
    public AjaxResult archivePage(@PathVariable Long pageId) {
        seoService.archivePage(pageId, getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:seo:list')")
    @GetMapping("/settings")
    public AjaxResult settings() {
        return success(seoService.getSettings());
    }

    @PreAuthorize("@ss.hasPermi('aid:seo:edit')")
    @PutMapping("/settings")
    @Log(title = "SEO 配置", businessType = BusinessType.UPDATE)
    public AjaxResult saveSettings(@RequestBody SeoModels.SettingsSave request) {
        seoService.saveSettings(request, getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:seo:edit')")
    @PostMapping("/scan")
    @Log(title = "SEO 页面扫描", businessType = BusinessType.OTHER)
    public AjaxResult scan() {
        return success(seoService.scan("ADMIN_SCAN", getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:seo:submit')")
    @PostMapping("/submit/baidu")
    @Log(title = "百度链接提交", businessType = BusinessType.OTHER)
    public AjaxResult submitBaidu(@RequestBody(required = false) SeoModels.SubmissionRequest request) {
        List<Long> ids = request == null ? List.of() : request.getPageIds();
        return success(seoService.submit(ids, "ADMIN_RETRY", getUserId(), getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:seo:submit')")
    @PostMapping("/submit/manual-confirm")
    @Log(title = "SEO 手动提交确认", businessType = BusinessType.UPDATE)
    public AjaxResult confirmManual(@RequestBody SeoModels.SubmissionRequest request) {
        return success(seoService.confirmManual(request == null ? List.of() : request.getPageIds(),
                getUserId(), getUsername()));
    }
}
