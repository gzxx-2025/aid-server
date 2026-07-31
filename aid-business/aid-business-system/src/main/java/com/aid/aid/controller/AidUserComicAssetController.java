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
import com.aid.aid.domain.AidUserComicAsset;
import com.aid.aid.service.IAidUserComicAssetService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 用户自定义漫画参考资产Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/assetuser")
public class AidUserComicAssetController extends BaseController
{
    @Autowired
    private IAidUserComicAssetService aidUserComicAssetService;

    /**
     * 查询用户自定义漫画参考资产列表
     */
    @PreAuthorize("@ss.hasPermi('aid:assetuser:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidUserComicAsset aidUserComicAsset)
    {
        startPage();
        List<AidUserComicAsset> list = aidUserComicAssetService.selectAidUserComicAssetList(aidUserComicAsset);
        return getDataTable(list);
    }

    /**
     * 导出用户自定义漫画参考资产列表
     */
    @PreAuthorize("@ss.hasPermi('aid:assetuser:export')")
    @Log(title = "用户自定义漫画参考资产", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidUserComicAsset aidUserComicAsset)
    {
        List<AidUserComicAsset> list = aidUserComicAssetService.selectAidUserComicAssetList(aidUserComicAsset);
        ExcelUtil<AidUserComicAsset> util = new ExcelUtil<AidUserComicAsset>(AidUserComicAsset.class);
        util.exportExcel(response, list, "用户自定义漫画参考资产数据");
    }

    /**
     * 获取用户自定义漫画参考资产详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:assetuser:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidUserComicAssetService.selectAidUserComicAssetById(id));
    }

    /**
     * 新增用户自定义漫画参考资产
     */
    @PreAuthorize("@ss.hasPermi('aid:assetuser:add')")
    @Log(title = "用户自定义漫画参考资产", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidUserComicAsset aidUserComicAsset)
    {
        return toAjax(aidUserComicAssetService.insertAidUserComicAsset(aidUserComicAsset));
    }

    /**
     * 修改用户自定义漫画参考资产
     */
    @PreAuthorize("@ss.hasPermi('aid:assetuser:edit')")
    @Log(title = "用户自定义漫画参考资产", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidUserComicAsset aidUserComicAsset)
    {
        return toAjax(aidUserComicAssetService.updateAidUserComicAsset(aidUserComicAsset));
    }

    /**
     * 删除用户自定义漫画参考资产
     */
    @PreAuthorize("@ss.hasPermi('aid:assetuser:remove')")
    @Log(title = "用户自定义漫画参考资产", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidUserComicAssetService.deleteAidUserComicAssetByIds(ids));
    }
}
