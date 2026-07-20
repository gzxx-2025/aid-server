package com.aid.voice.dto;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 音色库新增 / 更新请求 DTO（后台管理用）
 * 更新时必须携带 {@link #id}；新增时 {@link #id} 保持 null。
 * 后端会以 {@link #modelId} 反查 {@code aid_ai_model.provider_id} 回填 {@code provider_id} 字段，
 * 不信任前端传入的 providerId，此 DTO 中不提供 providerId 字段。
 *
 * @author 视觉AID
 */
@Data
public class VoiceLibraryUpsertRequest
{
    /** 主键（仅更新时传入） */
    private Long id;

    /** 所属模型ID（必填） */
    @NotNull(message = "模型不能为空")
    private Long modelId;

    /** 厂商侧真实音色编码（必填） */
    @NotBlank(message = "编码不能为空")
    @Size(max = 128, message = "编码过长")
    private String voiceCode;

    /** 展示名称（必填） */
    @NotBlank(message = "名称不能为空")
    @Size(max = 100, message = "名称过长")
    private String voiceName;

    /** 头像图 URL */
    @Size(max = 500, message = "头像过长")
    private String avatarUrl;

    /** 试听音频 URL */
    @Size(max = 500, message = "试听过长")
    private String sampleUrl;

    /** 试听文案 */
    @Size(max = 500, message = "文案过长")
    private String sampleText;

    /** 语言区域码（必填） */
    @NotBlank(message = "语言不能为空")
    private String language;

    /** 性别（必填） */
    @NotBlank(message = "性别不能为空")
    private String gender;

    /** 年龄段（必填） */
    @NotBlank(message = "年龄不能为空")
    private String ageRange;

    /** 角色类型 tag_code 列表 */
    private List<String> characterTypes;

    /** 使用场景 tag_code 列表 */
    private List<String> voiceStyles;

    /** 音调 tag_code 列表 */
    private List<String> toneTags;

    /** 情感编码列表 */
    private List<String> emotionTags;

    /** 是否支持情感 */
    private Boolean supportsEmotion;

    /** 是否支持语速 */
    private Boolean supportsSpeed;

    /** 是否支持音调 */
    private Boolean supportsPitch;

    /** 默认语速（0.5 ~ 2.0） */
    private BigDecimal defaultSpeed;

    /** 默认音调（-12 ~ 12） */
    private BigDecimal defaultPitch;

    /** 采样率 */
    private Integer sampleRate;

    /** 音频格式 */
    private String audioFormat;

    /** 下架时间（yyyy-MM-dd HH:mm:ss；不传或为空表示永不下架，后端会归一化为 9999-12-31 00:00:00） */
    private String offlineTime;

    /** 排序（越大越靠前） */
    private Integer sortOrder;

    /** 状态（0启用 1停用，默认 0） */
    private String status;

    /** 备注 */
    @Size(max = 500, message = "备注过长")
    private String remark;
}
