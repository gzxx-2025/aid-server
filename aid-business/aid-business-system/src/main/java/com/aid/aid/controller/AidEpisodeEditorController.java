package com.aid.aid.controller;

import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.service.IAidEpisodeEditorService;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.poi.ExcelUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 剧集视频剪辑与成片最新状态Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/pisodeeditor")
public class AidEpisodeEditorController extends BaseController
{
    @Autowired
    private IAidEpisodeEditorService aidEpisodeEditorService;

    /**
     * 查询剧集视频剪辑与成片最新状态列表
     */
    @PreAuthorize("@ss.hasPermi('aid:pisodeeditor:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidEpisodeEditor aidEpisodeEditor)
    {
        startPage();
        List<AidEpisodeEditor> list = aidEpisodeEditorService.selectAidEpisodeEditorList(aidEpisodeEditor);
        return getDataTable(list);
    }

    /**
     * 导出剧集视频剪辑与成片最新状态列表
     */
    @PreAuthorize("@ss.hasPermi('aid:pisodeeditor:export')")
    @Log(title = "剧集视频剪辑与成片最新状态", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidEpisodeEditor aidEpisodeEditor)
    {
        List<AidEpisodeEditor> list = aidEpisodeEditorService.selectAidEpisodeEditorList(aidEpisodeEditor);
        ExcelUtil<AidEpisodeEditor> util = new ExcelUtil<AidEpisodeEditor>(AidEpisodeEditor.class);
        util.exportExcel(response, list, "剧集视频剪辑与成片最新状态数据");
    }

    /**
     * 获取剧集视频剪辑与成片最新状态详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:pisodeeditor:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidEpisodeEditorService.selectAidEpisodeEditorById(id));
    }

    /**
     * 新增剧集视频剪辑与成片最新状态
     */
    @PreAuthorize("@ss.hasPermi('aid:pisodeeditor:add')")
    @Log(title = "剧集视频剪辑与成片最新状态", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidEpisodeEditor aidEpisodeEditor)
    {
        return toAjax(aidEpisodeEditorService.insertAidEpisodeEditor(aidEpisodeEditor));
    }

    /**
     * 修改剧集视频剪辑与成片最新状态
     */
    @PreAuthorize("@ss.hasPermi('aid:pisodeeditor:edit')")
    @Log(title = "剧集视频剪辑与成片最新状态", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidEpisodeEditor aidEpisodeEditor)
    {
        return toAjax(aidEpisodeEditorService.updateAidEpisodeEditor(aidEpisodeEditor));
    }

    /**
     * 删除剧集视频剪辑与成片最新状态
     */
    @PreAuthorize("@ss.hasPermi('aid:pisodeeditor:remove')")
    @Log(title = "剧集视频剪辑与成片最新状态", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidEpisodeEditorService.deleteAidEpisodeEditorByIds(ids));
    }
}
