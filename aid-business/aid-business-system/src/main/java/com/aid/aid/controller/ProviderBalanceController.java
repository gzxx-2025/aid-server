package com.aid.aid.controller;

import com.aid.aid.domain.ProviderBalanceConfig;
import com.aid.aid.domain.ProviderBalanceDelivery;
import com.aid.aid.domain.ProviderBalanceIncident;
import com.aid.aid.domain.ProviderBalanceRecipient;
import com.aid.aid.domain.AidProviderErrorRule;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.enums.BusinessType;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.rule.ErrorRuleService;
import com.aid.common.exception.ServiceException;
import com.aid.providerbalance.model.ProviderBalanceSettings;
import com.aid.providerbalance.service.ProviderBalanceQrService;
import com.aid.providerbalance.service.ProviderBalanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
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

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 供应商余额监控后台管理接口。 */
@RestController
@RequestMapping("/aid/provider-balance")
@RequiredArgsConstructor
@Tag(name = "供应商余额监控", description = "供应商余额检测、模拟余额、提醒人和告警事件管理")
public class ProviderBalanceController extends BaseController {
    private static final String BALANCE_ERROR_CODE = TaskErrorCode.PROVIDER_BALANCE_INSUFFICIENT.name();

    private final ProviderBalanceService balanceService;
    private final ProviderBalanceQrService qrService;
    private final ErrorRuleService errorRuleService;

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:list')")
    @GetMapping("/overview")
    public AjaxResult overview() {
        return success(balanceService.overview());
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:list')")
    @GetMapping("/providers")
    public AjaxResult providers() {
        return success(balanceService.listProviders());
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:list')")
    @GetMapping("/channels")
    public AjaxResult channels() {
        return success(balanceService.channelCapabilities());
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:edit')")
    @Log(title = "供应商余额全局配置", businessType = BusinessType.UPDATE)
    @PutMapping("/settings")
    public AjaxResult saveSettings(@RequestBody ProviderBalanceSettings settings) {
        balanceService.saveSettings(settings);
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:edit')")
    @Log(title = "供应商余额监控配置", businessType = BusinessType.UPDATE)
    @PutMapping("/providers/{providerId}")
    public AjaxResult saveProvider(@PathVariable Long providerId, @RequestBody ProviderBalanceConfig config) {
        balanceService.saveProviderConfig(providerId, config, getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:query')")
    @PostMapping("/providers/{providerId}/check")
    @Operation(summary = "立即检查一个已选择供应商")
    public AjaxResult checkProvider(@PathVariable Long providerId) {
        return success(balanceService.checkProvider(providerId));
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:edit')")
    @Log(title = "供应商模拟余额调整", businessType = BusinessType.INSERT)
    @PostMapping("/providers/{providerId}/adjustments")
    public AjaxResult adjustment(@PathVariable Long providerId, @RequestBody AdjustmentRequest request) {
        balanceService.addAdjustment(providerId, request.getAmount(), request.getType(), request.getRemark(), getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:list')")
    @GetMapping("/recipients")
    public AjaxResult recipients() {
        return success(balanceService.listRecipients());
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:edit')")
    @Log(title = "供应商余额提醒人", businessType = BusinessType.INSERT)
    @PostMapping("/recipients")
    public AjaxResult addRecipient(@RequestBody RecipientRequest request) {
        ProviderBalanceRecipient recipient = request.toEntity(balanceService);
        return success(balanceService.saveRecipient(recipient, request.getTargetValue(), getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:edit')")
    @Log(title = "供应商余额提醒人", businessType = BusinessType.UPDATE)
    @PutMapping("/recipients")
    public AjaxResult editRecipient(@RequestBody RecipientRequest request) {
        ProviderBalanceRecipient recipient = request.toEntity(balanceService);
        return success(balanceService.saveRecipient(recipient, request.getTargetValue(), getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:edit')")
    @Log(title = "供应商余额提醒人", businessType = BusinessType.DELETE)
    @DeleteMapping("/recipients/{ids}")
    public AjaxResult removeRecipients(@PathVariable Long[] ids) {
        balanceService.removeRecipients(Arrays.asList(ids));
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:test')")
    @PostMapping("/recipients/{recipientId}/test")
    public AjaxResult testRecipient(@PathVariable Long recipientId) {
        balanceService.testRecipient(recipientId);
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:edit')")
    @PostMapping("/recipients/wechat/qrcode")
    public AjaxResult createWechatQr(@RequestBody WechatQrRequest request) {
        return success(qrService.create(getUserId(), request.getRecipientName(), request.getProviderIds()));
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:edit')")
    @GetMapping("/recipients/wechat/qrcode/status")
    public AjaxResult wechatQrStatus(@RequestParam String sceneStr) {
        return success(qrService.status(getUserId(), sceneStr));
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:list')")
    @GetMapping("/incidents")
    public AjaxResult incidents(@RequestParam(required = false) Long providerId,
                                @RequestParam(required = false) String status) {
        startPage();
        List<ProviderBalanceIncident> list = balanceService.listIncidents(providerId, status);
        return success(pageData(getDataTable(list)));
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:list')")
    @GetMapping("/deliveries")
    public AjaxResult deliveries(@RequestParam(required = false) Long incidentId,
                                 @RequestParam(required = false) String status) {
        startPage();
        List<ProviderBalanceDelivery> list = balanceService.listDeliveries(incidentId, status);
        return success(pageData(getDataTable(list)));
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:list')")
    @GetMapping("/balance-rules")
    public AjaxResult balanceRules(@RequestParam(required = false) String providerCode) {
        startPage();
        List<AidProviderErrorRule> list = errorRuleService.list(
                providerCode, null, BALANCE_ERROR_CODE, null);
        return success(pageData(getDataTable(list)));
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:edit')")
    @Log(title = "供应商余额不足规则", businessType = BusinessType.INSERT)
    @PostMapping("/balance-rules")
    public AjaxResult addBalanceRule(@RequestBody AidProviderErrorRule rule) {
        if (rule == null) {
            throw new ServiceException("余额不足规则不能为空");
        }
        rule.setErrorCode(BALANCE_ERROR_CODE);
        return success(errorRuleService.add(rule, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:edit')")
    @Log(title = "供应商余额不足规则", businessType = BusinessType.UPDATE)
    @PutMapping("/balance-rules")
    public AjaxResult editBalanceRule(@RequestBody AidProviderErrorRule rule) {
        assertBalanceRule(rule == null ? null : rule.getId());
        rule.setErrorCode(BALANCE_ERROR_CODE);
        errorRuleService.update(rule, getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:edit')")
    @Log(title = "供应商余额不足规则启停", businessType = BusinessType.UPDATE)
    @PostMapping("/balance-rules/{id}/toggle")
    public AjaxResult toggleBalanceRule(@PathVariable Long id, @RequestBody RuleToggleRequest request) {
        assertBalanceRule(id);
        if (request == null || request.getEnabled() == null
                || (request.getEnabled() != 0 && request.getEnabled() != 1)) {
            throw new ServiceException("启停状态不正确");
        }
        errorRuleService.toggle(id, request.getEnabled(), getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:edit')")
    @Log(title = "供应商余额不足规则", businessType = BusinessType.DELETE)
    @DeleteMapping("/balance-rules/{ids}")
    public AjaxResult removeBalanceRules(@PathVariable Long[] ids) {
        for (Long id : ids) {
            assertBalanceRule(id);
        }
        errorRuleService.delete(Arrays.asList(ids));
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:providerbalance:edit')")
    @Log(title = "确认供应商余额告警", businessType = BusinessType.UPDATE)
    @PostMapping("/incidents/{incidentId}/acknowledge")
    public AjaxResult acknowledge(@PathVariable Long incidentId, @RequestBody(required = false) AcknowledgeRequest request) {
        balanceService.acknowledge(incidentId, getUsername(), request == null ? null : request.getSilenceMinutes());
        return success();
    }

    private Map<String, Object> pageData(TableDataInfo table) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", table.getRows());
        data.put("total", table.getTotal());
        return data;
    }

    private void assertBalanceRule(Long id) {
        if (id == null || !BALANCE_ERROR_CODE.equals(errorRuleService.get(id).getErrorCode())) {
            throw new ServiceException("余额不足规则不存在");
        }
    }

    @Data
    public static class RecipientRequest {
        private Long id;
        private String recipientName;
        private String channel;
        private String targetValue;
        private Integer enabled;
        private Integer dailyReportEnabled;
        private List<Long> providerIds;

        ProviderBalanceRecipient toEntity(ProviderBalanceService service) {
            ProviderBalanceRecipient entity = new ProviderBalanceRecipient();
            entity.setId(id);
            entity.setRecipientName(recipientName);
            entity.setChannel(channel);
            entity.setEnabled(enabled);
            entity.setDailyReportEnabled(dailyReportEnabled);
            entity.setProviderIds(service.normalizeProviderIds(providerIds));
            return entity;
        }
    }

    @Data
    public static class WechatQrRequest {
        private String recipientName;
        private List<Long> providerIds;
    }

    @Data
    public static class AdjustmentRequest {
        private BigDecimal amount;
        private String type;
        private String remark;
    }

    @Data
    public static class AcknowledgeRequest {
        private Integer silenceMinutes;
    }

    @Data
    public static class RuleToggleRequest {
        private Integer enabled;
    }
}
