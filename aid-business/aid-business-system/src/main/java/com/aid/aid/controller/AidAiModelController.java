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
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.controller.support.AiConfigJsonValidator;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.media.service.ConcurrencyConfigValidator;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * AI底层模型配置与算力计费Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/aidmodel")
public class AidAiModelController extends BaseController
{
    @Autowired
    private IAidAiModelService aidAiModelService;

    /** 参考音频等能力位归属按服务商编码判定，写入前需按 provider_id 反查 provider_code。 */
    @Autowired
    private IAidAiProviderService aidAiProviderService;

    /** 并发上限层级校验器（全局 ≥ 供应商 ≥ 模型） */
    @Autowired
    private ConcurrencyConfigValidator concurrencyConfigValidator;

    /**
     * 按 funcCode 查询可用模型池（供后台管理下拉使用）。
     *
     * @param funcCode 业务场景编码（= sceneCode = aid_ai_model_func_config.func_code）
     * @return 该场景下可选模型列表（按 modelIds 配置顺序）
     */
    @Autowired
    private IAiModelBusinessService aiModelBusinessService;

    @PreAuthorize("@ss.hasPermi('aid:aidmodel:list')")
    @GetMapping("/listByFunc")
    public AjaxResult listByFunc(String funcCode)
    {
        return success(aiModelBusinessService.listAvailableModelsByFuncCode(funcCode));
    }

    /**
     * 真实模型总览：按真实上游模型名聚合展示各厂商模型及启停状态，
     * 模型身份由「真实模型 + 模型代码」共同决定，同组内各模型代码可独立启停。
     *
     * @param keyword 搜索关键字（匹配真实模型名/展示码/展示名称，可空）
     * @return 真实模型分组列表
     */
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:list')")
    @GetMapping("/realModelOverview")
    public AjaxResult realModelOverview(String keyword)
    {
        return success(aidAiModelService.selectRealModelOverview(keyword));
    }

    /**
     * 查询AI底层模型配置与算力计费列表
     */
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidAiModel aidAiModel)
    {
        startPage();
        List<AidAiModel> list = aidAiModelService.selectAidAiModelList(aidAiModel);
        return getDataTable(list);
    }

    /**
     * 导出AI底层模型配置与算力计费列表
     */
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:export')")
    @Log(title = "AI底层模型配置与算力计费", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidAiModel aidAiModel)
    {
        List<AidAiModel> list = aidAiModelService.selectAidAiModelList(aidAiModel);
        ExcelUtil<AidAiModel> util = new ExcelUtil<AidAiModel>(AidAiModel.class);
        util.exportExcel(response, list, "AI底层模型配置与算力计费数据");
    }

    /**
     * 获取AI底层模型配置与算力计费详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidAiModelService.selectAidAiModelById(id));
    }

    /**
     * 新增AI底层模型配置与算力计费
     */
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:add')")
    @Log(title = "AI底层模型配置与算力计费", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidAiModel aidAiModel)
    {
        // 写入前统一校验所有 JSON 列（billing_rule_json / capability_json / 调度策略 等），
        // 避免非 JSON 字符串污染计费 / 调度 / 能力解析链路
        AiConfigJsonValidator.validate(aidAiModel, resolveProviderCode(aidAiModel));
        // 并发上限层级校验：模型上限不得超过所属供应商与全局上限
        concurrencyConfigValidator.validateModelSave(aidAiModel);
        return toAjax(aidAiModelService.insertAidAiModel(aidAiModel));
    }

    /**
     * 修改AI底层模型配置与算力计费
     */
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:edit')")
    @Log(title = "AI底层模型配置与算力计费", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidAiModel aidAiModel)
    {
        // 写入前统一校验所有 JSON 列，避免脏数据污染 DB
        AiConfigJsonValidator.validate(aidAiModel, resolveProviderCode(aidAiModel));
        // 并发上限层级校验：模型上限不得超过所属供应商与全局上限
        concurrencyConfigValidator.validateModelSave(aidAiModel);
        return toAjax(aidAiModelService.updateAidAiModel(aidAiModel));
    }

    /**
     * 反查模型所属服务商编码。
     * 能力位是否会被真正下发取决于 Provider 路由，而路由以 provider_code 为最高优先级，
     * 模型表只存 provider_id，故校验前必须补齐该值。
     *
     * @param model 待写入模型
     * @return 服务商编码；服务商不存在或未选择时返回 null
     */
    private String resolveProviderCode(AidAiModel model)
    {
        if (model == null)
        {
            return null;
        }
        Long providerId = model.getProviderId();
        if (providerId == null && model.getId() != null)
        {
            // 编辑表单未回传服务商时按库中已存值判定，避免合法模型被误判成「服务商未实现下发」
            AidAiModel persisted = aidAiModelService.selectAidAiModelById(model.getId());
            providerId = persisted == null ? null : persisted.getProviderId();
        }
        if (providerId == null)
        {
            return null;
        }
        AidAiProvider provider = aidAiProviderService.selectAidAiProviderById(providerId);
        return provider == null ? null : provider.getProviderCode();
    }

    /**
     * 删除AI底层模型配置与算力计费
     */
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:remove')")
    @Log(title = "AI底层模型配置与算力计费", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidAiModelService.deleteAidAiModelByIds(ids));
    }
}
