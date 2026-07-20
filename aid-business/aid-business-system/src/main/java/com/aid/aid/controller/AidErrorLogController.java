package com.aid.aid.controller;

import com.aid.aid.domain.AidErrorLog;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.error.rule.AidErrorLogQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 上游错误样本日志 Controller。
 * 主要给"未识别错误"页用：列出 matched_rule_id IS NULL 的样本，
 * 一键跳转到规则编辑器。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aid/errorlog")
@RequiredArgsConstructor
public class AidErrorLogController extends BaseController {

    private final AidErrorLogQueryService errorLogQueryService;

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
}
