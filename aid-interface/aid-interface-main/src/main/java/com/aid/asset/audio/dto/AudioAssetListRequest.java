package com.aid.asset.audio.dto;

import lombok.Data;

/**
 * 音频资产列表查询请求（C 端 / 后台共用字段）
 *
 * @author 视觉AID
 */
@Data
public class AudioAssetListRequest {

    /** 分页页码，从 1 起，默认 1 */
    private Integer pageNum;

    /** 分页条数，范围 1..100，默认 10 */
    private Integer pageSize;

    /** 项目ID（可选） */
    private Long projectId;

    /** 剧集ID（可选） */
    private Long episodeId;

    /** 分镜ID（可选） */
    private Long storyboardId;

    /** 音色库ID（可选） */
    private Long voiceLibraryId;

    /** 音色展示名模糊关键字（可选） */
    private String voiceName;

    /** 资产标题模糊关键字（可选） */
    private String assetTitle;

    /** 情感（可选，精确） */
    private String emotion;

    /** 来源（可选：1 AI生成 / 2 用户上传） */
    private Integer audioSource;

    /** 删除标志（后台可选：0未删除 / 2已删除，C端忽略） */
    private String delFlag;
}
