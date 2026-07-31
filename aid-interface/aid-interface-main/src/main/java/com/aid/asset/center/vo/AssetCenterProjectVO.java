package com.aid.asset.center.vo;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 资产中心-项目节点 VO（分类树的最外层，分页返回）。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AssetCenterProjectVO {

    /** 项目 ID */
    private Long projectId;

    /** 项目名称 */
    private String projectName;

    /** 项目类型：series 剧集 / movie 电影 */
    private String projectType;

    /** 该项目下的剧集列表（电影模式仅一条「全剧集」） */
    private List<AssetCenterEpisodeVO> episodes;
}
