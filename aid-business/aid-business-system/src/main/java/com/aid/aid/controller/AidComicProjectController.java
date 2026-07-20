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
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 漫剧项目主Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/aidproject")
public class AidComicProjectController extends BaseController
{
    @Autowired
    private IAidComicProjectService aidComicProjectService;

    /**
     * 查询漫剧项目主列表
     */
    @PreAuthorize("@ss.hasPermi('aid:aidproject:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidComicProject aidComicProject)
    {
        startPage();
        List<AidComicProject> list = aidComicProjectService.selectAidComicProjectList(aidComicProject);
        return getDataTable(list);
    }

    /**
     * 导出漫剧项目主列表
     */
    @PreAuthorize("@ss.hasPermi('aid:aidproject:export')")
    @Log(title = "漫剧项目主", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidComicProject aidComicProject)
    {
        List<AidComicProject> list = aidComicProjectService.selectAidComicProjectList(aidComicProject);
        ExcelUtil<AidComicProject> util = new ExcelUtil<AidComicProject>(AidComicProject.class);
        util.exportExcel(response, list, "漫剧项目主数据");
    }

    /**
     * 获取漫剧项目主详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:aidproject:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidComicProjectService.selectAidComicProjectById(id));
    }

    /**
     * 新增漫剧项目主
     */
    @PreAuthorize("@ss.hasPermi('aid:aidproject:add')")
    @Log(title = "漫剧项目主", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidComicProject aidComicProject)
    {
        return toAjax(aidComicProjectService.insertAidComicProject(aidComicProject));
    }

    /**
     * 修改漫剧项目主
     */
    @PreAuthorize("@ss.hasPermi('aid:aidproject:edit')")
    @Log(title = "漫剧项目主", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidComicProject aidComicProject)
    {
        return toAjax(aidComicProjectService.updateAidComicProject(aidComicProject));
    }

    /**
     * 删除漫剧项目主
     */
    @PreAuthorize("@ss.hasPermi('aid:aidproject:remove')")
    @Log(title = "漫剧项目主", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidComicProjectService.deleteAidComicProjectByIds(ids));
    }
}
