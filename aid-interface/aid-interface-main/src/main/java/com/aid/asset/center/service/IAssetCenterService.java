package com.aid.asset.center.service;

import java.util.Map;

import com.aid.asset.center.dto.AssetCenterCategoryTreeRequest;
import com.aid.asset.center.dto.AssetCenterDetailRequest;
import com.aid.asset.center.dto.AssetCenterListRequest;
import com.aid.asset.center.vo.AssetCenterDetailVO;

/**
 * 资产中心业务 Service。
 * 三个接口贯穿：分类树（项目→剧集→15 分类） → 个人资产列表（按分类筛选，精简） → 资产明细（完整内容）。
 * 全部按当前登录用户 userId 隔离，不越权、不含官方数据。
 *
 * @author 视觉AID
 */
public interface IAssetCenterService {

    /**
     * 分类树：项目（分页）→ 剧集 → 固定 15 个分类。
     *
     * @param request 查询条件（项目层分页 + 项目名关键字）
     * @param userId  当前登录用户 ID
     * @return 分页结果：total / pageNum / pageSize / list
     */
    Map<String, Object> categoryTree(AssetCenterCategoryTreeRequest request, Long userId);

    /**
     * 个人资产列表（精简，不含长正文）。
     *
     * @param request 查询条件（项目/剧集/分类均可选）
     * @param userId  当前登录用户 ID
     * @return 分页结果：total / pageNum / pageSize / list
     */
    Map<String, Object> listAssets(AssetCenterListRequest request, Long userId);

    /**
     * 资产明细（完整内容）。
     *
     * @param request 查询条件（分类编码 + 主键 ID）
     * @param userId  当前登录用户 ID
     * @return 明细 VO
     */
    AssetCenterDetailVO detail(AssetCenterDetailRequest request, Long userId);
}
