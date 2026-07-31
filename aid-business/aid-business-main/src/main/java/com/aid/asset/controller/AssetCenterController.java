package com.aid.asset.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.asset.center.dto.AssetCenterCategoryTreeRequest;
import com.aid.asset.center.dto.AssetCenterDetailRequest;
import com.aid.asset.center.dto.AssetCenterListRequest;
import com.aid.asset.center.service.IAssetCenterService;
import com.aid.asset.center.vo.AssetCenterDetailVO;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 资产中心 Controller（C 端）。
 * 三接口贯穿：分类树（项目→剧集→15 分类） → 个人资产列表（按分类筛选，精简） → 资产明细（完整内容）。
 * 全部仅返回当前登录用户自己的数据，不含官方、不越权。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/asset/center")
public class AssetCenterController extends BaseController {

    @Resource
    private IAssetCenterService assetCenterService;

    /**
     * 接口一：资产分类树。
     * 项目层分页；每个项目下挂剧集（电影固定一条「全剧集」episodeId=0），每个剧集下挂固定 15 个资产分类。
     */
    @PostMapping("/category/tree")
    public AjaxResult categoryTree(@RequestBody(required = false) AssetCenterCategoryTreeRequest request) {
        Long userId = SecurityUtils.getUserId();
        Map<String, Object> data = assetCenterService.categoryTree(request, userId);
        return AjaxResult.success("查询成功", data);
    }

    /**
     * 接口二：个人资产列表（精简，不返回任何长正文）。
     * 支持按项目/剧集/分类编码筛选，均可选；分类编码不传则返回该范围内全部分类混合分页。
     */
    @PostMapping("/list")
    public AjaxResult list(@RequestBody(required = false) AssetCenterListRequest request) {
        Long userId = SecurityUtils.getUserId();
        Map<String, Object> data = assetCenterService.listAssets(request, userId);
        return AjaxResult.success("查询成功", data);
    }

    /**
     * 接口三：资产明细（返回选中资产的完整内容）。
     * 入参 categoryCode + id，按分类路由到对应业务表查询，查询时强制校验归属当前用户。
     */
    @PostMapping("/detail")
    public AjaxResult detail(@RequestBody(required = false) AssetCenterDetailRequest request) {
        Long userId = SecurityUtils.getUserId();
        AssetCenterDetailVO data = assetCenterService.detail(request, userId);
        return AjaxResult.success("查询成功", data);
    }
}
