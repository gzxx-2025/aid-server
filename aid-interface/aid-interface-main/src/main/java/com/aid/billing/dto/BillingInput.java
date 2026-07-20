package com.aid.billing.dto;

import lombok.Data;

import java.util.Map;

/**
 * 统一计费输入：各业务模块（图片/视频/文本）提取计费参数后构造此对象，
 * 传入 BillingFacadeService，不依赖具体请求类。
 */
@Data
public class BillingInput {

    /** 媒体类型：TEXT / IMAGE / VIDEO */
    private String mediaType;

    /** 计费参数键值对（如 resolution=720P, duration=5, inputChars=1200） */
    private Map<String, Object> params;

    public BillingInput() {
    }

    public BillingInput(String mediaType, Map<String, Object> params) {
        this.mediaType = mediaType;
        this.params = params;
    }
}
