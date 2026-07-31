package com.aid.asset.center.dto;

import lombok.Data;

/**
 * 资产中心-资产明细查询请求 DTO。
 * 由列表接口选中某条资产后调用，按 categoryCode 路由到对应业务表查询完整内容。
 * 查询时强制校验该条归属当前登录用户，防越权。
 *
 * @author 视觉AID
 */
@Data
public class AssetCenterDetailRequest {

    /** 分类编码（必传，见 AssetCenterCategoryEnum） */
    private String categoryCode;

    /** 资产主键 ID（必传，对应该分类业务表的主键） */
    private Long id;
}
