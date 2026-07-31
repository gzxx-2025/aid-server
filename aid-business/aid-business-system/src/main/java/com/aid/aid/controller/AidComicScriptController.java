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
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.service.IAidComicScriptService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 剧本原文与简化版Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/aidscript")
public class AidComicScriptController extends BaseController
{
    @Autowired
    private IAidComicScriptService aidComicScriptService;

    /**
     * 查询剧本原文与简化版列表
     */
    @PreAuthorize("@ss.hasPermi('aid:aidscript:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidComicScript aidComicScript)
    {
        startPage();
        List<AidComicScript> list = aidComicScriptService.selectAidComicScriptList(aidComicScript);
        return getDataTable(list);
    }

    /**
     * 导出剧本原文与简化版列表
     */
    @PreAuthorize("@ss.hasPermi('aid:aidscript:export')")
    @Log(title = "剧本原文与简化版", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidComicScript aidComicScript)
    {
        List<AidComicScript> list = aidComicScriptService.selectAidComicScriptList(aidComicScript);
        ExcelUtil<AidComicScript> util = new ExcelUtil<AidComicScript>(AidComicScript.class);
        util.exportExcel(response, list, "剧本原文与简化版数据");
    }

    /**
     * 获取剧本原文与简化版详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:aidscript:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidComicScriptService.selectAidComicScriptById(id));
    }

    /**
     * 新增剧本原文与简化版
     */
    @PreAuthorize("@ss.hasPermi('aid:aidscript:add')")
    @Log(title = "剧本原文与简化版", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidComicScript aidComicScript)
    {
        return toAjax(aidComicScriptService.insertAidComicScript(aidComicScript));
    }

    /**
     * 修改剧本原文与简化版
     */
    @PreAuthorize("@ss.hasPermi('aid:aidscript:edit')")
    @Log(title = "剧本原文与简化版", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidComicScript aidComicScript)
    {
        return toAjax(aidComicScriptService.updateAidComicScript(aidComicScript));
    }

    /**
     * 删除剧本原文与简化版
     */
    @PreAuthorize("@ss.hasPermi('aid:aidscript:remove')")
    @Log(title = "剧本原文与简化版", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidComicScriptService.deleteAidComicScriptByIds(ids));
    }
}
