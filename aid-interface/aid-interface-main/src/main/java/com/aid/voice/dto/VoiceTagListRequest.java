package com.aid.voice.dto;

import lombok.Data;

/**
 * 音色标签字典查询请求
 *
 * @author 视觉AID
 */
@Data
public class VoiceTagListRequest
{
    /** 分页页码，从 1 起，默认 1 */
    private Integer pageNum;

    /** 分页条数 1..100，默认 10 */
    private Integer pageSize;

    /** 标签类型（必须：character_type / voice_style / tone） */
    private String tagType;

    /** 标签编码模糊关键字 */
    private String tagCode;

    /** 标签名称模糊关键字 */
    private String tagName;

    /** 状态：0启用 1停用 */
    private String status;

    /** 删除标志（后台可选：0未删除 / 2已删除） */
    private String delFlag;
}
