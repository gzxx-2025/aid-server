package com.aid.voice.dto;

import java.util.List;

import lombok.Data;

/**
 * 音色选择同步请求 DTO。
 * 前端从远程列表多选后提交：selectedVoiceCodes 入库，removedVoiceCodes 软删。
 */
@Data
public class VoiceSyncApplyRequest
{
    /** 目标模型ID（aid_ai_model.id，必须是 MiniMax audio 模型） */
    private Long modelId;

    /** 用户选中要入库的 voiceCode 列表（远程有 + 用户勾选） */
    private List<String> selectedVoiceCodes;

    /** 用户取消选择的 voiceCode 列表（本地有但用户取消勾选 → 软删） */
    private List<String> removedVoiceCodes;
}
