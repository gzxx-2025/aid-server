package com.aid.tokendance.controller;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import com.aid.tokendance.catalog.ITokenDanceCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** TokenDance 在线模型目录管理。 */
@RestController
@RequestMapping("/aid/tokendance/catalog")
@RequiredArgsConstructor
@Tag(name = "TokenDance 模型目录", description = "浏览可信在线目录、预览能力与费用差异，并导入停用草稿")
public class TokenDanceCatalogController extends BaseController {
    private final ITokenDanceCatalogService catalog;

    @GetMapping
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:list')")
    @Operation(summary = "浏览 TokenDance 在线模型目录")
    public AjaxResult list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long providerId) {
        return success(catalog.list(keyword, providerId));
    }

    @GetMapping("/status")
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:list')")
    @Operation(summary = "查询在线模型目录状态")
    public AjaxResult status() { return success(catalog.status()); }

    @PostMapping("/refresh")
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:list')")
    @Operation(summary = "刷新在线模型目录", description = "合并相同的进行中请求；失败时继续使用最近可信快照")
    public AjaxResult refresh() { return success(catalog.refresh()); }

    @GetMapping("/{modelId}")
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:query')")
    @Operation(summary = "查看模型目录详情与官方能力证据")
    public AjaxResult detail(@PathVariable String modelId) { return success(catalog.detail(modelId)); }

    @GetMapping("/{modelId}/preview")
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:query')")
    @Operation(summary = "预览模型能力与费用配置", description = "只读预览，不修改已有模型")
    public AjaxResult preview(@PathVariable String modelId, @RequestParam Long providerId, @RequestParam String protocol) {
        return success(catalog.preview(providerId, modelId, protocol));
    }

    @PostMapping("/import")
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:add')")
    @Operation(summary = "幂等导入模型草稿", description = "仅新建停用草稿，已有模型保持不变")
    public AjaxResult importModels(@RequestBody ImportRequest request) {
        if (request == null) {
            logger.info("TokenDance 目录导入缺少模型选择");
            throw new ServiceException("模型选择无效");
        }
        return success(catalog.importModels(request.providerId(), request.catalogVersion(), request.selections()));
    }

    public record ImportRequest(
            Long providerId,
            String catalogVersion,
            List<ITokenDanceCatalogService.Selection> selections) { }

    @PostMapping("/{modelId}/repair-empty-pricing")
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:edit')")
    @Operation(summary = "补齐已有模型的空 SKU", description = "仅对成本完整且 SKU 为空的模型生效；校验配置版本，不覆盖能力、倍率及启用状态")
    public AjaxResult repairEmptyPricing(@PathVariable String modelId, @RequestBody RepairPricingRequest request) {
        if (request == null) {
            logger.info("TokenDance SKU 修复缺少参数: modelId={}", modelId);
            throw new ServiceException("模型参数无效");
        }
        catalog.repairEmptyPricing(request.providerId(), modelId, request.protocol(), request.configVersion());
        return success();
    }

    public record RepairPricingRequest(Long providerId, String protocol, Long configVersion) { }
}
