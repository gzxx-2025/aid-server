package com.aid.asset.center.vo;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 资产中心-剧集节点 VO（挂在项目下，电影模式固定一条 {0, "全剧集"}）。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AssetCenterEpisodeVO {

    /** 剧集 ID（电影模式固定为 0） */
    private Long episodeId;

    /** 剧集名称（电影模式固定为「全剧集」） */
    private String episodeName;

    /** 该剧集下的固定 15 个资产分类 */
    private List<AssetCenterCategoryVO> categories;
}
