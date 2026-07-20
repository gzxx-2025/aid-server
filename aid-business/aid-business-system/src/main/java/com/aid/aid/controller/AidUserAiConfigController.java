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
import com.aid.aid.domain.AidUserAiConfig;
import com.aid.aid.service.IAidUserAiConfigService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 用户自定义AI大模型配置(配置覆盖用)Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/diymodelconfig")
public class AidUserAiConfigController extends BaseController
{
    @Autowired
    private IAidUserAiConfigService aidUserAiConfigService;

    /**
     * 查询用户自定义AI大模型配置(配置覆盖用)列表
     */
    @PreAuthorize("@ss.hasPermi('aid:diymodelconfig:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidUserAiConfig aidUserAiConfig)
    {
        startPage();
        List<AidUserAiConfig> list = aidUserAiConfigService.selectAidUserAiConfigList(aidUserAiConfig);
        return getDataTable(list);
    }

    /**
     * 导出用户自定义AI大模型配置(配置覆盖用)列表
     */
    @PreAuthorize("@ss.hasPermi('aid:diymodelconfig:export')")
    @Log(title = "用户自定义AI大模型配置(配置覆盖用)", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidUserAiConfig aidUserAiConfig)
    {
        List<AidUserAiConfig> list = aidUserAiConfigService.selectAidUserAiConfigList(aidUserAiConfig);
        ExcelUtil<AidUserAiConfig> util = new ExcelUtil<AidUserAiConfig>(AidUserAiConfig.class);
        util.exportExcel(response, list, "用户自定义AI大模型配置(配置覆盖用)数据");
    }

    /**
     * 获取用户自定义AI大模型配置(配置覆盖用)详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:diymodelconfig:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidUserAiConfigService.selectAidUserAiConfigById(id));
    }

    /**
     * 新增用户自定义AI大模型配置(配置覆盖用)
     */
    @PreAuthorize("@ss.hasPermi('aid:diymodelconfig:add')")
    @Log(title = "用户自定义AI大模型配置(配置覆盖用)", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidUserAiConfig aidUserAiConfig)
    {
        return toAjax(aidUserAiConfigService.insertAidUserAiConfig(aidUserAiConfig));
    }

    /**
     * 修改用户自定义AI大模型配置(配置覆盖用)
     */
    @PreAuthorize("@ss.hasPermi('aid:diymodelconfig:edit')")
    @Log(title = "用户自定义AI大模型配置(配置覆盖用)", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidUserAiConfig aidUserAiConfig)
    {
        return toAjax(aidUserAiConfigService.updateAidUserAiConfig(aidUserAiConfig));
    }

    /**
     * 删除用户自定义AI大模型配置(配置覆盖用)
     */
    @PreAuthorize("@ss.hasPermi('aid:diymodelconfig:remove')")
    @Log(title = "用户自定义AI大模型配置(配置覆盖用)", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidUserAiConfigService.deleteAidUserAiConfigByIds(ids));
    }
}
