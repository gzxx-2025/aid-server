package com.aid.asset.center.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 资产中心-分类节点 VO（挂在每个剧集下，固定 15 个）。
 *
 * @author 视觉AID
 */
@Data
@AllArgsConstructor
public class AssetCenterCategoryVO {

    /** 分类编码 */
    private String categoryCode;

    /** 分类中文名称 */
    private String categoryName;
}
