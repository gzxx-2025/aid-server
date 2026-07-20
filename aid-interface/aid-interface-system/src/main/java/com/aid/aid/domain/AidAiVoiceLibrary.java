package com.aid.aid.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * AI音色库对象 aid_ai_voice_library。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_ai_voice_library")
public class AidAiVoiceLibrary extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /** 所属服务商ID（冗余自 aid_ai_model.provider_id，查询用） */
    @Excel(name = "所属服务商ID")
    private Long providerId;

    /** 所属AI模型ID（主归属，关联 aid_ai_model.id） */
    @Excel(name = "所属AI模型ID")
    private Long modelId;

    /** 厂商侧真实 voice_id / 音色编码（调用 TTS 时原样透传） */
    @Excel(name = "音色编码")
    private String voiceCode;
    /** 展示名称，如"甜美少女音" */
    @Excel(name = "展示名称")
    private String voiceName;

    /** 头像图 URL */
    @Excel(name = "头像图URL")
    private String avatarUrl;

    /** 试听音频 URL */
    @Excel(name = "试听音频URL")
    private String sampleUrl;

    /** 试听文案（展示用） */
    @Excel(name = "试听文案")
    private String sampleText;
    /** 语言区域码：zh-CN / en-US / ja-JP */
    @Excel(name = "语言区域码")
    private String language;

    /** 性别：female / male / neutral */
    @Excel(name = "性别")
    private String gender;

    /** 年龄段：child / teen / young / adult / middle / elderly */
    @Excel(name = "年龄段")
    private String ageRange;
    /** 角色类型 tag_code 列表（来源 aid_ai_voice_tag，tag_type=character_type） */
    @Excel(name = "角色类型标签")
    private String characterTypes;

    /** 使用场景 tag_code 列表（来源 aid_ai_voice_tag，tag_type=voice_style） */
    @Excel(name = "使用场景标签")
    private String voiceStyles;

    /** 音调风格 tag_code 列表（来源 aid_ai_voice_tag，tag_type=tone） */
    @Excel(name = "音调风格标签")
    private String toneTags;

    /** 擅长情感编码列表（供应商原生编码，须命中所属模型 capability_json.emotions 白名单） */
    @Excel(name = "情感标签")
    private String emotionTags;
    /** 是否支持情感参数 */
    @Excel(name = "是否支持情感")
    private Boolean supportsEmotion;

    /** 是否支持语速参数 */
    @Excel(name = "是否支持语速")
    private Boolean supportsSpeed;

    /** 是否支持音调参数 */
    @Excel(name = "是否支持音调")
    private Boolean supportsPitch;

    /** 默认语速（合法范围 0.5 ~ 2.0） */
    @Excel(name = "默认语速")
    private BigDecimal defaultSpeed;

    /** 默认音调（合法范围 -12 ~ 12） */
    @Excel(name = "默认音调")
    private BigDecimal defaultPitch;

    /** 采样率，示例 16000 / 24000 / 48000 */
    @Excel(name = "采样率")
    private Integer sampleRate;

    /** 音频格式：mp3 / wav / pcm */
    @Excel(name = "音频格式")
    private String audioFormat;

    /**
     * 下架时间。
     */
    @Excel(name = "下架时间", dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date offlineTime;
    /** 排序（越大越靠前） */
    @Excel(name = "排序")
    private Integer sortOrder;

    /** 状态：0启用 1停用 */
    @Excel(name = "状态")
    private String status;

    /** 删除标志：0存在 2删除 */
    private String delFlag;

}
