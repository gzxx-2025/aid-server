package com.aid.aid.controller;

import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.service.IAidRolePropSceneFormService;
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
 * 角色道具场景形态(从)Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/rolepropsceneform")
public class AidRolePropSceneFormController extends BaseController
{
    @Autowired
    private IAidRolePropSceneFormService aidRolePropSceneFormService;

    /**
     * 查询角色道具场景形态(从)列表
     */
    @PreAuthorize("@ss.hasPermi('aid:rolepropsceneform:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidRolePropSceneForm aidRolePropSceneForm)
    {
        startPage();
        List<AidRolePropSceneForm> list = aidRolePropSceneFormService.selectAidRolePropSceneFormList(aidRolePropSceneForm);
        return getDataTable(list);
    }

    /**
     * 导出角色道具场景形态(从)列表
     */
    @PreAuthorize("@ss.hasPermi('aid:rolepropsceneform:export')")
    @Log(title = "角色道具场景形态(从)", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidRolePropSceneForm aidRolePropSceneForm)
    {
        List<AidRolePropSceneForm> list = aidRolePropSceneFormService.selectAidRolePropSceneFormList(aidRolePropSceneForm);
        ExcelUtil<AidRolePropSceneForm> util = new ExcelUtil<AidRolePropSceneForm>(AidRolePropSceneForm.class);
        util.exportExcel(response, list, "角色道具场景形态(从)数据");
    }

    /**
     * 获取角色道具场景形态(从)详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:rolepropsceneform:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidRolePropSceneFormService.selectAidRolePropSceneFormById(id));
    }

    /**
     * 新增角色道具场景形态(从)
     */
    @PreAuthorize("@ss.hasPermi('aid:rolepropsceneform:add')")
    @Log(title = "角色道具场景形态(从)", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidRolePropSceneForm aidRolePropSceneForm)
    {
        return toAjax(aidRolePropSceneFormService.insertAidRolePropSceneForm(aidRolePropSceneForm));
    }

    /**
     * 修改角色道具场景形态(从)
     */
    @PreAuthorize("@ss.hasPermi('aid:rolepropsceneform:edit')")
    @Log(title = "角色道具场景形态(从)", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidRolePropSceneForm aidRolePropSceneForm)
    {
        return toAjax(aidRolePropSceneFormService.updateAidRolePropSceneForm(aidRolePropSceneForm));
    }

    /**
     * 删除角色道具场景形态(从)
     */
    @PreAuthorize("@ss.hasPermi('aid:rolepropsceneform:remove')")
    @Log(title = "角色道具场景形态(从)", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidRolePropSceneFormService.deleteAidRolePropSceneFormByIds(ids));
    }
}
