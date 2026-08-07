package com.aid.aid.controller;

import com.aid.aid.domain.AidErrorLog;
import com.aid.aid.domain.AidProviderErrorRule;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.enums.BusinessType;
import com.aid.common.error.rule.AidErrorLogQueryService;
import com.aid.common.error.rule.ErrorLogRuleConversionService;
import com.aid.common.exception.ServiceException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 上游错误样本日志 Controller。
 * 主要给"未识别错误"页用：列出 matched_rule_id IS NULL 的样本，
 * 支持基于错误样本创建规则并回写处理结果。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aid/errorlog")
@RequiredArgsConstructor
public class AidErrorLogController extends BaseController {

    private final AidErrorLogQueryService errorLogQueryService;
    private final ErrorLogRuleConversionService ruleConversionService;

    /** 列表 */
    @PreAuthorize("@ss.hasPermi('aid:errorlog:list')")
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam(required = false) String providerCode,
                              @RequestParam(required = false) Boolean onlyUnmatched) {
        startPage();
        List<AidErrorLog> list = errorLogQueryService.list(providerCode, onlyUnmatched);
        return getDataTable(list);
    }

    /** 详情 */
    @PreAuthorize("@ss.hasPermi('aid:errorlog:list')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(errorLogQueryService.get(id));
    }

    /**
     * 根据未识别错误样本生成规则草稿，供管理员确认和调整。
     */
    @PreAuthorize("@ss.hasPermi('aid:errorlog:convert')")
    @GetMapping("/{id}/rule-draft")
    public AjaxResult getRuleDraft(@PathVariable("id") Long id) {
        return success(ruleConversionService.buildDraft(id));
    }

    /**
     * 把错误样本转换为规则，并同步回写该样本的命中规则与错误码。
     */
    @PreAuthorize("@ss.hasPermi('aid:errorlog:convert')")
    @Log(title = "错误样本转规则", businessType = BusinessType.INSERT)
    @PostMapping("/convert")
    public AjaxResult convert(@RequestBody ConvertRequest request) {
        if (Objects.isNull(request)) {
            log.info("[ErrorRuleConvert] 请求参数缺失");
            throw new ServiceException("请求参数缺失");
        }
        Long ruleId = ruleConversionService.convert(
                request.getErrorLogId(), request.getRule(), getUsername());
        return success(ruleId);
    }

    /** 错误样本转规则请求。 */
    @Data
    public static class ConvertRequest {
        /** 来源错误样本 ID。 */
        private Long errorLogId;

        /** 管理员确认后的完整规则。 */
        private AidProviderErrorRule rule;
    }
}
