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
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 分镜时间轴主Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/storyboard")
public class AidStoryboardController extends BaseController
{
    @Autowired
    private IAidStoryboardService aidStoryboardService;

    /**
     * 查询分镜时间轴主列表
     */
    @PreAuthorize("@ss.hasPermi('aid:storyboard:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidStoryboard aidStoryboard)
    {
        startPage();
        List<AidStoryboard> list = aidStoryboardService.selectAidStoryboardList(aidStoryboard);
        return getDataTable(list);
    }

    /**
     * 导出分镜时间轴主列表
     */
    @PreAuthorize("@ss.hasPermi('aid:storyboard:export')")
    @Log(title = "分镜时间轴主", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidStoryboard aidStoryboard)
    {
        List<AidStoryboard> list = aidStoryboardService.selectAidStoryboardList(aidStoryboard);
        ExcelUtil<AidStoryboard> util = new ExcelUtil<AidStoryboard>(AidStoryboard.class);
        util.exportExcel(response, list, "分镜时间轴主数据");
    }

    /**
     * 获取分镜时间轴主详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:storyboard:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidStoryboardService.selectAidStoryboardById(id));
    }

    /**
     * 新增分镜时间轴主
     */
    @PreAuthorize("@ss.hasPermi('aid:storyboard:add')")
    @Log(title = "分镜时间轴主", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidStoryboard aidStoryboard)
    {
        return toAjax(aidStoryboardService.insertAidStoryboard(aidStoryboard));
    }

    /**
     * 修改分镜时间轴主
     */
    @PreAuthorize("@ss.hasPermi('aid:storyboard:edit')")
    @Log(title = "分镜时间轴主", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidStoryboard aidStoryboard)
    {
        return toAjax(aidStoryboardService.updateAidStoryboard(aidStoryboard));
    }

    /**
     * 删除分镜时间轴主
     */
    @PreAuthorize("@ss.hasPermi('aid:storyboard:remove')")
    @Log(title = "分镜时间轴主", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidStoryboardService.deleteAidStoryboardByIds(ids));
    }
}
