package com.aid.aid.controller;

import com.aid.aid.domain.AidProviderErrorRule;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.enums.BusinessType;
import com.aid.common.error.ErrorNormalizer;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.error.rule.ErrorRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上游错误归一化规则 Controller。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aid/errorrule")
@RequiredArgsConstructor
public class AidProviderErrorRuleController extends BaseController {

    /** 测试器输入上限：64KB，避免管理员误贴超大文本拖累内存 */
    private static final int MAX_TEST_RAW_CHARS = 64 * 1024;

    private final ErrorRuleService errorRuleService;

    /** 列表查询 */
    @PreAuthorize("@ss.hasPermi('aid:errorrule:list')")
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam(required = false) String providerCode,
                              @RequestParam(required = false) String modelCode,
                              @RequestParam(required = false) String errorCode,
                              @RequestParam(required = false) Integer enabled) {
        startPage();
        List<AidProviderErrorRule> list = errorRuleService.list(providerCode, modelCode, errorCode, enabled);
        return getDataTable(list);
    }

    /** 详情 */
    @PreAuthorize("@ss.hasPermi('aid:errorrule:query')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(errorRuleService.get(id));
    }

    /** 新增 */
    @PreAuthorize("@ss.hasPermi('aid:errorrule:add')")
    @Log(title = "错误规则", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidProviderErrorRule rule) {
        errorRuleService.add(rule, getUsername());
        return success();
    }

    /** 修改 */
    @PreAuthorize("@ss.hasPermi('aid:errorrule:edit')")
    @Log(title = "错误规则", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidProviderErrorRule rule) {
        errorRuleService.update(rule, getUsername());
        return success();
    }

    /** 删除（多个 ID 用逗号分隔） */
    @PreAuthorize("@ss.hasPermi('aid:errorrule:remove')")
    @Log(title = "错误规则", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable("ids") Long[] ids) {
        errorRuleService.delete(Arrays.asList(ids));
        return success();
    }

    /** 启停切换 */
    @PreAuthorize("@ss.hasPermi('aid:errorrule:edit')")
    @Log(title = "错误规则启停", businessType = BusinessType.UPDATE)
    @PostMapping("/toggle")
    public AjaxResult toggle(@RequestBody ToggleRequest req) {
        errorRuleService.toggle(req.getId(), req.getEnabled(), getUsername());
        return success();
    }

    /**
     * 测试器：贴一段原始错误，看会命中哪条规则、最终的 errorCode。
     * 注意：调用 {@link ErrorNormalizer#dryRun} 走"只查不写"路径，避免污染未识别错误日志。
     */
    @PreAuthorize("@ss.hasPermi('aid:errorrule:test')")
    @PostMapping("/test")
    public AjaxResult test(@RequestBody TestRequest req) {
        // 防御：测试器 rawMessage 上限 64KB，避免管理员误贴超大文本拖累内存
        String raw = req.getRawMessage();
        if (raw != null && raw.length() > MAX_TEST_RAW_CHARS) {
            log.warn("[ErrorRule] 测试器输入超长被截断, len={}", raw.length());
            raw = raw.substring(0, MAX_TEST_RAW_CHARS);
        }
        TaskErrorResult result = ErrorNormalizer.dryRun(
                req.getProviderCode(),
                req.getModelCode(),
                req.getHttpStatus() == null ? -1 : req.getHttpStatus(),
                raw);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", result.getErrorCode());
        data.put("errorType", result.getErrorType());
        data.put("errorSource", result.getErrorSource());
        data.put("userMessage", result.getUserMessage());
        data.put("retryable", result.isRetryable());
        data.put("needRecharge", result.isNeedRecharge());
        return success(data);
    }

    /**
     * 全量重建缓存（DB → 内存 + 本地文件）。
     */
    @PreAuthorize("@ss.hasPermi('aid:errorrule:refresh')")
    @Log(title = "错误规则缓存重建", businessType = BusinessType.UPDATE)
    @PostMapping("/cache/rebuild")
    public AjaxResult rebuildCache() {
        errorRuleService.rebuildCache();
        return success();
    }

    /**
     * 错误码枚举：返回 {@link TaskErrorCode} 全部值供前端下拉选择。
     */
    @PreAuthorize("@ss.hasPermi('aid:errorrule:query')")
    @GetMapping("/error-codes")
    public AjaxResult listErrorCodes() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TaskErrorCode code : TaskErrorCode.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code.name());
            item.put("errorType", code.getErrorType());
            item.put("errorSource", code.getErrorSource());
            item.put("userMessage", code.getUserMessage());
            item.put("retryable", code.isRetryable());
            item.put("needRecharge", code.isNeedRecharge());
            list.add(item);
        }
        return success(list);
    }
    @lombok.Data
    public static class ToggleRequest {
        /** 规则 ID */
        private Long id;
        /** 启用 (0 禁 1 启) */
        private Integer enabled;
    }

    @lombok.Data
    public static class TestRequest {
        /** 厂商编码 */
        private String providerCode;
        /** 模型编码 */
        private String modelCode;
        /** HTTP 状态码（可选） */
        private Integer httpStatus;
        /** 原始错误体 */
        private String rawMessage;
    }
}
