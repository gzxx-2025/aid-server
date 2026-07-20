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
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 角色道具场景Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/scenecp")
public class AidRolePropSceneController extends BaseController
{
    @Autowired
    private IAidRolePropSceneService aidRolePropSceneService;

    /**
     * 查询角色道具场景列表
     */
    @PreAuthorize("@ss.hasPermi('aid:scenecp:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidRolePropScene aidRolePropScene)
    {
        startPage();
        List<AidRolePropScene> list = aidRolePropSceneService.selectAidRolePropSceneList(aidRolePropScene);
        return getDataTable(list);
    }

    /**
     * 导出角色道具场景列表
     */
    @PreAuthorize("@ss.hasPermi('aid:scenecp:export')")
    @Log(title = "角色道具场景", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidRolePropScene aidRolePropScene)
    {
        List<AidRolePropScene> list = aidRolePropSceneService.selectAidRolePropSceneList(aidRolePropScene);
        ExcelUtil<AidRolePropScene> util = new ExcelUtil<AidRolePropScene>(AidRolePropScene.class);
        util.exportExcel(response, list, "角色道具场景数据");
    }

    /**
     * 获取角色道具场景详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:scenecp:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidRolePropSceneService.selectAidRolePropSceneById(id));
    }

    /**
     * 新增角色道具场景
     */
    @PreAuthorize("@ss.hasPermi('aid:scenecp:add')")
    @Log(title = "角色道具场景", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidRolePropScene aidRolePropScene)
    {
        return toAjax(aidRolePropSceneService.insertAidRolePropScene(aidRolePropScene));
    }

    /**
     * 修改角色道具场景
     */
    @PreAuthorize("@ss.hasPermi('aid:scenecp:edit')")
    @Log(title = "角色道具场景", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidRolePropScene aidRolePropScene)
    {
        return toAjax(aidRolePropSceneService.updateAidRolePropScene(aidRolePropScene));
    }

    /**
     * 删除角色道具场景
     */
    @PreAuthorize("@ss.hasPermi('aid:scenecp:remove')")
    @Log(title = "角色道具场景", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidRolePropSceneService.deleteAidRolePropSceneByIds(ids));
    }
}
