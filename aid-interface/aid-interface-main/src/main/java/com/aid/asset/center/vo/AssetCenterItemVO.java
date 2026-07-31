package com.aid.asset.center.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 资产中心-个人资产列表项 VO（精简，不含任何长正文）。
 * 列表只暴露主键、分类、名称、媒体地址、归属与时间，正文内容请走明细接口。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AssetCenterItemVO {

    /** 主键 ID（对应该分类业务表的主键，配合 categoryCode 调明细接口） */
    private Long id;

    /** 分类编码 */
    private String categoryCode;

    /** 分类中文名称 */
    private String categoryName;

    /** 资产名称（无独立名称的分类按规则生成展示名） */
    private String name;

    /** 媒体地址：图片/视频/音频的 URL（无图的分类为 null，出参拼域名） */
    @MediaUrl
    private String mediaUrl;

    /** 所属项目 ID */
    private Long projectId;

    /** 所属剧集 ID（电影模式为 0） */
    private Long episodeId;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
