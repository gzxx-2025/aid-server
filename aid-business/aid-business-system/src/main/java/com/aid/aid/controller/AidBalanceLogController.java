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
import com.aid.aid.domain.AidBalanceLog;
import com.aid.aid.service.IAidBalanceLogService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 余额变动记录Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/balancelog")
public class AidBalanceLogController extends BaseController
{
    @Autowired
    private IAidBalanceLogService aidBalanceLogService;

    /**
     * 查询余额变动记录列表
     */
    @PreAuthorize("@ss.hasPermi('aid:balancelog:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidBalanceLog aidBalanceLog)
    {
        startPage();
        List<AidBalanceLog> list = aidBalanceLogService.selectAidBalanceLogList(aidBalanceLog);
        return getDataTable(list);
    }

    /**
     * 导出余额变动记录列表
     */
    @PreAuthorize("@ss.hasPermi('aid:balancelog:export')")
    @Log(title = "余额变动记录", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidBalanceLog aidBalanceLog)
    {
        List<AidBalanceLog> list = aidBalanceLogService.selectAidBalanceLogList(aidBalanceLog);
        ExcelUtil<AidBalanceLog> util = new ExcelUtil<AidBalanceLog>(AidBalanceLog.class);
        util.exportExcel(response, list, "余额变动记录数据");
    }

    /**
     * 获取余额变动记录详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:balancelog:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidBalanceLogService.selectAidBalanceLogById(id));
    }

    /**
     * 新增余额变动记录
     * 余额流水是审计账本，禁止任何 UI 层 CRUD，否则会破坏 AccountUpdateServiceImpl 的幂等判断。
     */
    @PreAuthorize("@ss.hasPermi('aid:balancelog:add')")
    @Log(title = "余额变动记录", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidBalanceLog aidBalanceLog)
    {
        return AjaxResult.error(403, "余额流水为审计账本，禁止手动新增");
    }

    /**
     * 修改余额变动记录
     * 余额流水禁止手动修改。
     */
    @PreAuthorize("@ss.hasPermi('aid:balancelog:edit')")
    @Log(title = "余额变动记录", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidBalanceLog aidBalanceLog)
    {
        return AjaxResult.error(403, "余额流水为审计账本，禁止手动修改");
    }

    /**
     * 删除余额变动记录
     * 余额流水禁止删除。
     */
    @PreAuthorize("@ss.hasPermi('aid:balancelog:remove')")
    @Log(title = "余额变动记录", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return AjaxResult.error(403, "余额流水为审计账本，禁止手动删除");
    }
}
