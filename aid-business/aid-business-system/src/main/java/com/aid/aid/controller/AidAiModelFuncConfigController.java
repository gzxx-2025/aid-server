package com.aid.aid.controller;

import com.aid.aid.domain.AidAiModelFuncConfig;
import com.aid.aid.service.IAidAiModelFuncConfigService;
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
import com.aid.orchestration.IAiOrchestrationService;

/**
 * AI模型功能配置Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/funcconfig")
public class AidAiModelFuncConfigController extends BaseController
{
    @Autowired
    private IAidAiModelFuncConfigService aidAiModelFuncConfigService;

    @Autowired
    private IAiOrchestrationService orchestrationService;

    @Autowired
    private com.aid.model.definition.ModelBusinessBindingService modelBindings;

    /**
     * 查询AI模型功能配置列表
     */
    @PreAuthorize("@ss.hasAnyPermi('aid:funcconfig:query,aid:funcconfig:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidAiModelFuncConfig aidAiModelFuncConfig)
    {
        startPage();
        List<AidAiModelFuncConfig> list = aidAiModelFuncConfigService.selectAidAiModelFuncConfigList(aidAiModelFuncConfig);
        return getDataTable(list);
    }

    /**
     * 导出AI模型功能配置列表
     */
    @PreAuthorize("@ss.hasPermi('aid:funcconfig:export')")
    @Log(title = "AI模型功能配置", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidAiModelFuncConfig aidAiModelFuncConfig)
    {
        List<AidAiModelFuncConfig> list = aidAiModelFuncConfigService.selectAidAiModelFuncConfigList(aidAiModelFuncConfig);
        ExcelUtil<AidAiModelFuncConfig> util = new ExcelUtil<AidAiModelFuncConfig>(AidAiModelFuncConfig.class);
        util.exportExcel(response, list, "AI模型功能配置数据");
    }

    /**
     * 获取AI模型功能配置详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:funcconfig:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        AidAiModelFuncConfig config = aidAiModelFuncConfigService.selectAidAiModelFuncConfigById(id);
        if (config != null) config.setModelBindings(modelBindings.forFunction(config.getFuncCode()));
        return success(config);
    }

    /**
     * 新增AI模型功能配置
     */
    @PreAuthorize("@ss.hasPermi('aid:funcconfig:add')")
    @Log(title = "AI模型功能配置", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidAiModelFuncConfig aidAiModelFuncConfig)
    {
        return toAjax(modelBindings.saveFunction(aidAiModelFuncConfig, true, getUsername()));
    }

    /**
     * 修改AI模型功能配置
     */
    @PreAuthorize("@ss.hasPermi('aid:funcconfig:edit')")
    @Log(title = "AI模型功能配置", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidAiModelFuncConfig aidAiModelFuncConfig)
    {
        return toAjax(modelBindings.saveFunction(aidAiModelFuncConfig, false, getUsername()));
    }

    /**
     * 删除AI模型功能配置
     */
    @PreAuthorize("@ss.hasPermi('aid:funcconfig:remove')")
    @Log(title = "AI模型功能配置", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(modelBindings.deleteFunctions(ids));
    }
}
