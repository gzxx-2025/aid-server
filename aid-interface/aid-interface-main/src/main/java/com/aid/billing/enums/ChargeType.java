package com.aid.billing.enums;

import lombok.Getter;

/**
 * 计费业务类型：文本 / 图片 / 视频
 */
@Getter
public enum ChargeType {

    TEXT("TEXT", "文本生成"),
    IMAGE("IMAGE", "图片生成"),
    VIDEO("VIDEO", "视频生成");

    private final String code;
    private final String desc;

    ChargeType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
