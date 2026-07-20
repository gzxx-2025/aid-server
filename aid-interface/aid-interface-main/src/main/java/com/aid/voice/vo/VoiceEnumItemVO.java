package com.aid.voice.vo;

import lombok.Data;

/**
 * 基础枚举候选项 VO（language / gender / age_range）
 *
 * @author 视觉AID
 */
@Data
public class VoiceEnumItemVO
{
    /** 编码 */
    private String code;

    /** 中文展示 */
    private String name;

    public VoiceEnumItemVO() {}

    public VoiceEnumItemVO(String code, String name)
    {
        this.code = code;
        this.name = name;
    }
}
