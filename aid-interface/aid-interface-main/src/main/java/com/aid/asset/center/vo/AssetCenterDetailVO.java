package com.aid.asset.center.vo;

import java.util.Date;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 资产中心-资产明细 VO（返回选中资产的完整内容）。
 * 媒体地址用独立的带 {@code @MediaUrl} 字段承载（自动拼域名）；
 * 其余文本 / JSON / 标量字段统一放入 {@code content} Map，键含义按分类在接口文档中说明。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AssetCenterDetailVO {

    /** 主键 ID */
    private Long id;

    /** 分类编码 */
    private String categoryCode;

    /** 分类中文名称 */
    private String categoryName;

    /** 资产名称 / 展示名 */
    private String name;

    /** 所属项目 ID */
    private Long projectId;

    /** 所属剧集 ID（电影模式为 0） */
    private Long episodeId;

    /** 图片地址（仅图片类分类有值，出参拼域名） */
    @MediaUrl
    private String imageUrl;

    /** 视频地址（仅视频类分类有值，出参拼域名） */
    @MediaUrl
    private String videoUrl;

    /** 音频地址（仅配音类分类有值，出参拼域名） */
    @MediaUrl
    private String audioUrl;

    /** 封面地址（仅预览视频分类有值，出参拼域名） */
    @MediaUrl
    private String coverUrl;

    /** 分类专属的完整内容字段（键含义见接口文档，含剧本正文/设定提示词/分镜脚本等长内容） */
    private Map<String, Object> content;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
