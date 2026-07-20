package com.aid.voice.vo;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;

/**
 * 音色库 VO（后台详情 / 列表返回结构）
 * 标签数组字段以 {@code List<String>} 暴露；C 端返回时由业务层根据白名单裁剪敏感字段。
 *
 * @author 视觉AID
 */
@Data
public class VoiceLibraryVO
{
    /** 主键 */
    private Long id;

    /** 服务商ID */
    private Long providerId;

    /** 服务商展示名 */
    private String providerName;

    /** 模型ID */
    private Long modelId;

    /** 模型展示名 */
    private String modelName;

    /** 音色编码 */
    private String voiceCode;

    /** 展示名 */
    private String voiceName;

    /** 头像URL（出参拼域名） */
    @MediaUrl
    private String avatarUrl;

    /** 试听URL（出参拼域名） */
    @MediaUrl
    private String sampleUrl;

    /** 试听文案 */
    private String sampleText;

    /** 语言 */
    private String language;

    /** 性别 */
    private String gender;

    /** 年龄段 */
    private String ageRange;

    /** 角色类型标签列表 */
    private List<String> characterTypes;

    /** 使用场景标签列表 */
    private List<String> voiceStyles;

    /** 音调标签列表 */
    private List<String> toneTags;

    /** 情感标签列表 */
    private List<String> emotionTags;

    /** 是否支持情感 */
    private Boolean supportsEmotion;

    /** 是否支持语速 */
    private Boolean supportsSpeed;

    /** 是否支持音调 */
    private Boolean supportsPitch;

    /** 默认语速 */
    private BigDecimal defaultSpeed;

    /** 默认音调 */
    private BigDecimal defaultPitch;

    /** 采样率 */
    private Integer sampleRate;

    /** 音频格式 */
    private String audioFormat;

    /** 下架时间（9999-12-31 表示永不下架） */
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date offlineTime;

    /** 排序 */
    private Integer sortOrder;

    /** 状态（C 端响应中不返回） */
    private String status;

    /** 删除标志（C 端响应中不返回） */
    private String delFlag;

    /** 备注（C 端响应中不返回） */
    private String remark;

    /** 创建时间（C 端响应中不返回） */
    private Date createTime;

    /** 更新时间（C 端响应中不返回） */
    private Date updateTime;
}
