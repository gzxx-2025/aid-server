package com.aid.aid.controller;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.controller.support.AiConfigJsonValidator;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;
import lombok.extern.slf4j.Slf4j;

/**
 * AI大模型服务商(官方渠道)配置Controller
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aid/aidprovider")
public class AidAiProviderController extends BaseController
{
    @Autowired
    private IAidAiProviderService aidAiProviderService;

    /**
     * 查询AI大模型服务商(官方渠道)配置列表
     */
    @PreAuthorize("@ss.hasPermi('aid:aidprovider:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidAiProvider aidAiProvider)
    {
        startPage();
        List<AidAiProvider> list = aidAiProviderService.selectAidAiProviderList(aidAiProvider);
        return getDataTable(list);
    }

    /**
     * 导出AI大模型服务商(官方渠道)配置列表
     */
    @PreAuthorize("@ss.hasPermi('aid:aidprovider:export')")
    @Log(title = "AI大模型服务商(官方渠道)配置", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidAiProvider aidAiProvider)
    {
        List<AidAiProvider> list = aidAiProviderService.selectAidAiProviderList(aidAiProvider);
        ExcelUtil<AidAiProvider> util = new ExcelUtil<AidAiProvider>(AidAiProvider.class);
        util.exportExcel(response, list, "AI大模型服务商(官方渠道)配置数据");
    }

    /**
     * 获取AI大模型服务商(官方渠道)配置详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:aidprovider:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidAiProviderService.selectAidAiProviderById(id));
    }

    /**
     * 新增AI大模型服务商(官方渠道)配置
     * 记录审计日志，便于追溯上游渠道的创建者。
     */
    @PreAuthorize("@ss.hasPermi('aid:aidprovider:add')")
    @Log(title = "AI大模型服务商(官方渠道)配置", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidAiProvider aidAiProvider)
    {
        // 写入前统一校验所有 JSON 列（schedule_strategy_json / extra_*），
        // 避免 UUID / 普通字符串误填到 JSON 字段污染 resolveStrategy 等下游链路
        AiConfigJsonValidator.validate(aidAiProvider);
        log.warn("[AUDIT-PROVIDER] 新增 provider, operator={}, providerCode={}, baseUrl={}",
                getUsername(),
                aidAiProvider == null ? null : aidAiProvider.getProviderCode(),
                aidAiProvider == null ? null : aidAiProvider.getBaseUrl());
        return toAjax(aidAiProviderService.insertAidAiProvider(aidAiProvider));
    }

    /**
     * 修改AI大模型服务商(官方渠道)配置
     * 记录审计日志，标记 baseUrl / 密钥是否变更（仅标记是否变更，不打印密钥明文）。
     */
    @PreAuthorize("@ss.hasPermi('aid:aidprovider:edit')")
    @Log(title = "AI大模型服务商(官方渠道)配置", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidAiProvider aidAiProvider)
    {
        // 写入前统一校验所有 JSON 列，避免脏数据污染 DB
        AiConfigJsonValidator.validate(aidAiProvider);
        if (aidAiProvider != null && aidAiProvider.getId() != null) {
            AidAiProvider old = aidAiProviderService.selectAidAiProviderById(aidAiProvider.getId());
            if (old != null) {
                boolean apiKeyChanged = aidAiProvider.getApiKey() != null
                        && !aidAiProvider.getApiKey().isEmpty()
                        && !aidAiProvider.getApiKey().equals(old.getApiKey());
                boolean apiSecretChanged = aidAiProvider.getApiSecret() != null
                        && !aidAiProvider.getApiSecret().isEmpty()
                        && !aidAiProvider.getApiSecret().equals(old.getApiSecret());
                boolean baseUrlChanged = aidAiProvider.getBaseUrl() != null
                        && !java.util.Objects.equals(aidAiProvider.getBaseUrl(), old.getBaseUrl());
                log.warn("[AUDIT-PROVIDER] 修改 provider, operator={}, id={}, code={}, "
                                + "baseUrlChanged={} ({} -> {}), apiKeyChanged={}, apiSecretChanged={}",
                        getUsername(), old.getId(), old.getProviderCode(),
                        baseUrlChanged, old.getBaseUrl(), aidAiProvider.getBaseUrl(),
                        apiKeyChanged, apiSecretChanged);
            }
        }
        return toAjax(aidAiProviderService.updateAidAiProvider(aidAiProvider));
    }

    /**
     * 删除AI大模型服务商(官方渠道)配置
     * 记录审计日志，便于追溯 provider 下线。
     */
    @PreAuthorize("@ss.hasPermi('aid:aidprovider:remove')")
    @Log(title = "AI大模型服务商(官方渠道)配置", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        log.warn("[AUDIT-PROVIDER] 删除 provider, operator={}, ids={}",
                getUsername(), java.util.Arrays.toString(ids));
        return toAjax(aidAiProviderService.deleteAidAiProviderByIds(ids));
    }
}
