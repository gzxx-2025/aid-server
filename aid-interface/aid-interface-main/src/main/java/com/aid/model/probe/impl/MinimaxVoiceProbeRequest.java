package com.aid.model.probe.impl;

import com.alibaba.fastjson2.annotation.JSONField;

import lombok.Data;

/**
 * MiniMax 音色查询请求。
 */
@Data
public class MinimaxVoiceProbeRequest {

    /** 查询音色类型 */
    @JSONField(name = "voice_type")
    private String voiceType;
}
