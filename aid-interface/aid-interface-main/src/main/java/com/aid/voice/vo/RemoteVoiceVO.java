package com.aid.voice.vo;

import lombok.Data;

/**
 * 远程音色展示 VO。
 * 供前端"同步音色"弹窗渲染列表用；每条包含远程信息 + 是否已入库标记。
 */
@Data
public class RemoteVoiceVO
{
    /** 音色编码（voice_id） */
    private String voiceCode;

    /** 音色名称（系统音色有 voice_name；克隆/文生音色可能为空） */
    private String voiceName;

    /** 描述（多条拼接） */
    private String description;

    /** 是否已入库（本地 aid_ai_voice_library 中存在且 del_flag='0'） */
    private Boolean exists;
}
