package com.aid.media.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

/**
 * 视频生成参考音频输入。
 *
 * @author 视觉AID
 */
@Data
public class ReferenceAudioInput
{
    /** 来源类型：由提示词占位推导出的角色音色试听样音。 */
    public static final String SOURCE_VOICE_SAMPLE = "VOICE_SAMPLE";

    /** 来源类型：用户显式选择的音频记录。 */
    public static final String SOURCE_AUDIO_RECORD = "AUDIO_RECORD";

    /** 来源类型：用户显式选择的上传参考音频。 */
    public static final String SOURCE_UPLOAD = "UPLOAD";

    /** 提示词中的引用序号；用户上传音频可为空。 */
    private Integer index;

    /** 引用展示名。 */
    private String name;

    /** 来源类型：VOICE_SAMPLE / AUDIO_RECORD / UPLOAD。 */
    private String sourceType;

    /** 角色资产 ID。 */
    private Long assetId;

    /** 角色音色绑定 ID。 */
    private Long bindingId;

    /** 音色库 ID。 */
    private Long voiceLibraryId;

    /** 用户音频记录 ID。 */
    private Long audioRecordId;

    /** 用户上传参考音频 ID。 */
    private Long referenceAudioId;

    /** 音色展示名。 */
    private String voiceName;

    /** 参考音频完整 URL。 */
    private String sampleUrl;

    /** 音频格式，如 wav / mp3。 */
    private String format;

    /** 音频时长，毫秒。 */
    private Integer durationMs;

    /**
     * 是否用户显式选择：显式来源校验失败必须报错，提示词推导出的隐式来源降级剔除。
     *
     * @return 显式选择返回 true
     */
    @JsonIgnore
    public boolean isExplicit()
    {
        return SOURCE_AUDIO_RECORD.equals(sourceType) || SOURCE_UPLOAD.equals(sourceType);
    }
}
