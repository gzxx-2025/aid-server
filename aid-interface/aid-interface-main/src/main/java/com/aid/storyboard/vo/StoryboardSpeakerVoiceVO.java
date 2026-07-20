package com.aid.storyboard.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 分镜发言角色的音色绑定信息VO（列表/详情展示用）。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class StoryboardSpeakerVoiceVO {

    /** 发言角色主名（台词标记第一个下划线前，如 罗峰） */
    private String roleName;

    /** 匹配到的角色资产ID（aid_role_prop_scene.id）；未匹配为 null */
    private Long assetId;

    /** 该角色是否已绑定音色（aid_role_voice_binding 启用行） */
    private boolean voiceBound;

    /** 绑定音色库ID（aid_ai_voice_library.id）；未绑定为 null */
    private Long voiceLibraryId;

    /** 绑定音色厂商编码；未绑定为 null */
    private String voiceCode;

    /** 绑定音色展示名；未绑定为 null */
    private String voiceName;
}
