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
import com.aid.common.utils.SecurityUtils;
import com.aid.orchestration.IAiOrchestrationService;
import com.aid.orchestration.dto.ModelPoolBindingChangeRequest;
import com.aid.orchestration.dto.ModelPoolBindingQueryRequest;

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

    @Autowired
    private com.aid.model.definition.ModelDefinitionService modelDefinitions;

    @Autowired
    private com.aid.model.definition.ModelProtocolRegistry modelProtocols;

    @Autowired
    private com.aid.model.definition.ModelMigrationService modelMigration;

    @Autowired
    private com.aid.model.definition.ModelMigrationJournal migrationJournal;

    @PreAuthorize("@ss.hasPermi('aid:aidmodel:edit') and @ss.hasPermi('aid:funcconfig:edit')")
    @GetMapping("/migrations")
    public AjaxResult migrations() { return success(migrationJournal.recent()); }

    @PreAuthorize("@ss.hasPermi('aid:aidmodel:edit') and @ss.hasPermi('aid:funcconfig:edit')")
    @PostMapping("/migrations/{id}/rollback")
    @Log(title = "恢复模型迁移配置", businessType = BusinessType.UPDATE)
    public AjaxResult rollbackMigration(@PathVariable Long id) {
        return toAjax(migrationJournal.rollback(id, SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:aidmodel:edit')")
    @GetMapping("/migration-preview")
    @io.swagger.v3.oas.annotations.Operation(summary = "预检模型身份、能力与配置冲突")
    public AjaxResult migrationPreview() {
        return success(modelMigration.preview());
    }

    @PreAuthorize("@ss.hasPermi('aid:aidmodel:edit') and @ss.hasPermi('aid:funcconfig:edit')")
    @PostMapping("/migrate")
    @Log(title = "合并模型能力", businessType = BusinessType.UPDATE)
    @io.swagger.v3.oas.annotations.Operation(summary = "按已核验的映射迁移模型", description = "校验所有源记录版本及能力覆盖，在同一事务内更新模型、旧标识和业务绑定。")
    public AjaxResult migrate(@RequestBody com.aid.model.definition.ModelMigrationService.MigrationRequest request) {
        return toAjax(modelMigration.apply(request, SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:aidmodel:list')")
    @GetMapping("/protocol-options")
    @io.swagger.v3.oas.annotations.Operation(summary = "查询模型类型支持的调用协议")
    public AjaxResult protocolOptions(String modelType) {
        return success(modelProtocols.options(modelType));
    }

    @lombok.Data
    public static class ParameterPreviewRequest {
        private com.aid.aid.domain.model.ModelCapabilityDefinition definition;
        private java.util.Map<String, Object> parameters;
    }

    @PreAuthorize("@ss.hasAnyPermi('aid:aidmodel:add,aid:aidmodel:edit')")
    @PostMapping("/parameter-preview")
    @io.swagger.v3.oas.annotations.Operation(summary = "校验模型参数表单", description = "仅运行参数约束，不提交生成任务或产生费用。")
    public AjaxResult parameterPreview(@RequestBody ParameterPreviewRequest request) {
        return success(modelDefinitions.preview(request.getDefinition(), request.getParameters()));
    }

    /** 参考音频等能力位归属按服务商编码判定，写入前需按 provider_id 反查 provider_code。 */
    @Autowired
    private IAidAiProviderService aidAiProviderService;

    @Autowired
    private com.aid.model.service.ModelSkuCoverageService skuCoverageService;

    @PreAuthorize("@ss.hasPermi('aid:aidmodel:edit')")
    @PostMapping("/sku-coverage")
    @io.swagger.v3.oas.annotations.Operation(summary = "检查模型能力与SKU覆盖", description = "只读检查当前草稿的有限计费组合，返回缺价组合、条件冲突与待核验项；不创建价格或任务。")
    public AjaxResult skuCoverage(@RequestBody AidAiModel draft) {
        AidAiModel effective = new AidAiModel();
        if (draft.getId() != null) {
            AidAiModel current = aidAiModelService.selectAidAiModelById(draft.getId());
            if (current == null) throw new com.aid.common.exception.ServiceException("模型不存在");
            cn.hutool.core.bean.BeanUtil.copyProperties(current, effective);
        }
        String priorCapability = effective.getCapabilityJson();
        String priorBilling = effective.getBillingRuleJson();
        cn.hutool.core.bean.BeanUtil.copyProperties(draft, effective,
                cn.hutool.core.bean.copier.CopyOptions.create().setIgnoreNullValue(true));
        effective.setCapabilityJson(com.aid.aid.service.support.ModelConfigurationMerge.merge(priorCapability, draft.getCapabilityJson()));
        effective.setBillingRuleJson(com.aid.aid.service.support.ModelConfigurationMerge.merge(priorBilling, draft.getBillingRuleJson()));
        return AjaxResult.success(skuCoverageService.inspect(effective));
    }

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

    /** 跨模型池、智能体、矩阵和项目配置执行一致的引用校验。 */
    @Autowired
    private IAiOrchestrationService orchestrationService;

    @PreAuthorize("@ss.hasPermi('aid:aidmodel:list')")
    @GetMapping("/listByFunc")
    public AjaxResult listByFunc(String funcCode)
    {
        return success(aiModelBusinessService.listAvailableModelsByFuncCode(funcCode));
    }

    /**
     * 真实模型总览：按真实模型标识展示各供应商的模型及统一启停状态。
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

    @PreAuthorize("@ss.hasAnyPermi('aid:funcconfig:query,aid:funcconfig:list,aid:funcconfig:edit,aid:aidmodel:list')")
    @PostMapping("/pool-bindings/query")
    @io.swagger.v3.oas.annotations.Operation(summary = "查询模型池绑定关系", description = "返回有效模型、模型池及当前有序绑定关系；模型列表为空时查询全部模型。")
    public AjaxResult getModelPoolBindings(@RequestBody(required = false) ModelPoolBindingQueryRequest request)
    {
        return success(orchestrationService.getModelPoolBindings(request));
    }

    @PreAuthorize("@ss.hasPermi('aid:funcconfig:edit')")
    @Log(title = "模型批量绑定模型池", businessType = BusinessType.UPDATE)
    @PostMapping("/pool-bindings/bind")
    @io.swagger.v3.oas.annotations.Operation(summary = "批量绑定模型池", description = "按模型池事务更新有序模型关系；重复绑定保持幂等，不修改模型能力、SKU、价格或状态。")
    public AjaxResult bindModelsToPools(@RequestBody ModelPoolBindingChangeRequest request)
    {
        return success(orchestrationService.bindModelsToPools(request, SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('aid:funcconfig:edit')")
    @Log(title = "模型批量移出模型池", businessType = BusinessType.UPDATE)
    @PostMapping("/pool-bindings/unbind")
    @io.swagger.v3.oas.annotations.Operation(summary = "批量移出模型池", description = "按模型池事务移除关系；重复移除保持幂等，启用模型池不得被清空，活动引用仍受统一编排校验保护。")
    public AjaxResult unbindModelsFromPools(@RequestBody ModelPoolBindingChangeRequest request)
    {
        return success(orchestrationService.unbindModelsFromPools(request, SecurityUtils.getUsername()));
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
        modelDefinitions.preload(list);
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
        return success(modelDefinitions.detail(id));
    }

    /**
     * 新增AI底层模型配置与算力计费
     */
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:add')")
    @Log(title = "AI底层模型配置与算力计费", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidAiModel aidAiModel)
    {
        checkBusinessBindingPermission(aidAiModel);
        // 写入前统一校验所有 JSON 列（billing_rule_json / capability_json / 调度策略 等），
        // 避免非 JSON 字符串污染计费 / 调度 / 能力解析链路
        AiConfigJsonValidator.validate(aidAiModel, resolveProviderCode(aidAiModel));
        // 并发上限层级校验：模型上限不得超过所属供应商与全局上限
        concurrencyConfigValidator.validateModelSave(aidAiModel);
        return toAjax(modelDefinitions.save(aidAiModel, true));
    }

    /**
     * 修改AI底层模型配置与算力计费
     */
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:edit')")
    @Log(title = "AI底层模型配置与算力计费", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidAiModel aidAiModel)
    {
        checkBusinessBindingPermission(aidAiModel);
        if (aidAiModel.getConfigVersion() == null) {
            throw new com.aid.common.exception.ServiceException("请刷新后保存配置");
        }
        AidAiModel current = aidAiModelService.selectAidAiModelById(aidAiModel.getId());
        if (current == null) {
            throw new com.aid.common.exception.ServiceException("模型不存在");
        }
        // 校验合并后的完整配置，而非把局部更新误当整份配置；实际写入仍由服务层执行 CAS。
        AidAiModel effective = new AidAiModel();
        cn.hutool.core.bean.BeanUtil.copyProperties(current, effective);
        cn.hutool.core.bean.BeanUtil.copyProperties(aidAiModel, effective,
                cn.hutool.core.bean.copier.CopyOptions.create().setIgnoreNullValue(true));
        effective.setCapabilityJson(com.aid.aid.service.support.ModelConfigurationMerge.merge(
                current.getCapabilityJson(), aidAiModel.getCapabilityJson()));
        effective.setBillingRuleJson(com.aid.aid.service.support.ModelConfigurationMerge.merge(
                current.getBillingRuleJson(), aidAiModel.getBillingRuleJson()));
        if (aidAiModel.getModelCode() == null) aidAiModel.setModelCode(current.getModelCode());
        AiConfigJsonValidator.validate(effective, resolveProviderCode(effective));
        // 并发上限层级校验：模型上限不得超过所属供应商与全局上限
        concurrencyConfigValidator.validateModelSave(effective);
        return toAjax(modelDefinitions.save(aidAiModel, false));
    }

    private void checkBusinessBindingPermission(AidAiModel model) {
        if (model.getBusinessBindings() != null && !SecurityUtils.hasPermi("aid:funcconfig:edit")) {
            throw new com.aid.common.exception.ServiceException("没有修改业务绑定的权限");
        }
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
        return toAjax(orchestrationService.deleteModelsIfUnreferenced(ids, SecurityUtils.getUsername()));
    }
}
