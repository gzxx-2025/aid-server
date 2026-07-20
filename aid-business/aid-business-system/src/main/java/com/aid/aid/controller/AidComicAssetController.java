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
import com.aid.aid.domain.AidComicAsset;
import com.aid.aid.service.IAidComicAssetService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 项目提取资产Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/aidcomicasset")
public class AidComicAssetController extends BaseController
{
    @Autowired
    private IAidComicAssetService aidComicAssetService;

    /**
     * 查询项目提取资产列表
     */
    @PreAuthorize("@ss.hasPermi('aid:aidcomicasset:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidComicAsset aidComicAsset)
    {
        startPage();
        List<AidComicAsset> list = aidComicAssetService.selectAidComicAssetList(aidComicAsset);
        return getDataTable(list);
    }

    /**
     * 导出项目提取资产列表
     */
    @PreAuthorize("@ss.hasPermi('aid:aidcomicasset:export')")
    @Log(title = "项目提取资产", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidComicAsset aidComicAsset)
    {
        List<AidComicAsset> list = aidComicAssetService.selectAidComicAssetList(aidComicAsset);
        ExcelUtil<AidComicAsset> util = new ExcelUtil<AidComicAsset>(AidComicAsset.class);
        util.exportExcel(response, list, "项目提取资产数据");
    }

    /**
     * 获取项目提取资产详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:aidcomicasset:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidComicAssetService.selectAidComicAssetById(id));
    }

    /**
     * 新增项目提取资产
     */
    @PreAuthorize("@ss.hasPermi('aid:aidcomicasset:add')")
    @Log(title = "项目提取资产", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidComicAsset aidComicAsset)
    {
        return toAjax(aidComicAssetService.insertAidComicAsset(aidComicAsset));
    }

    /**
     * 修改项目提取资产
     */
    @PreAuthorize("@ss.hasPermi('aid:aidcomicasset:edit')")
    @Log(title = "项目提取资产", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidComicAsset aidComicAsset)
    {
        return toAjax(aidComicAssetService.updateAidComicAsset(aidComicAsset));
    }

    /**
     * 删除项目提取资产
     */
    @PreAuthorize("@ss.hasPermi('aid:aidcomicasset:remove')")
    @Log(title = "项目提取资产", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidComicAssetService.deleteAidComicAssetByIds(ids));
    }
}
