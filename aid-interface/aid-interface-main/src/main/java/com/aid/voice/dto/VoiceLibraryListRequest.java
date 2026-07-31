package com.aid.voice.dto;

import lombok.Data;

/**
 * 音色库查询请求（后台管理 / C 端共用字段，Controller 按需透传）
 *
 * @author 视觉AID
 */
@Data
public class VoiceLibraryListRequest
{
    /** 分页页码，从 1 起，默认 1 */
    private Integer pageNum;

    /** 分页条数，范围 1..100，默认 10 */
    private Integer pageSize;

    /** 服务商ID（可选） */
    private Long providerId;

    /** 模型ID（可选） */
    private Long modelId;

    /** 语言（zh-CN / en-US / ja-JP，可选） */
    private String language;

    /** 性别（female / male / neutral，可选） */
    private String gender;

    /** 年龄段（可选） */
    private String ageRange;

    /** 状态（0启用 1停用，可选；C 端忽略此字段，一律按启用+未删除过滤） */
    private String status;

    /** 删除标志（后台可选：0未删除 / 2已删除，C端忽略） */
    private String delFlag;

    /** 音色展示名模糊关键字 */
    private String voiceName;

    /** 音色编码模糊关键字 */
    private String voiceCode;

    /** 单标签值过滤：角色类型 tag_code */
    private String characterType;

    /** 单标签值过滤：使用场景 tag_code */
    private String voiceStyle;

    /** 单标签值过滤：音调 tag_code */
    private String toneTag;

    /** 单标签值过滤：情感编码 */
    private String emotionTag;
}
