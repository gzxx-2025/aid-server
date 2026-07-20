package com.aid.aid.domain;

import java.io.Serializable;
import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.annotation.Excel;
import com.aid.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 角色音色绑定对象 aid_role_voice_binding。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_role_voice_binding")
public class AidRoleVoiceBinding extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /** 角色ID（aid_role_prop_scene.id，要求 asset_type=character） */
    @Excel(name = "角色ID")
    private Long assetId;

    /** 项目ID（冗余，查询过滤用） */
    @Excel(name = "项目ID")
    private Long projectId;

    /** 剧集ID（冗余） */
    @Excel(name = "剧集ID")
    private Long episodeId;

    /** 用户ID（冗余，归属校验用） */
    @Excel(name = "用户ID")
    private Long userId;
    /** 所绑定音色ID（aid_ai_voice_library.id） */
    @Excel(name = "音色ID")
    private Long voiceLibraryId;

    /** 冗余：音色厂商编码（调用 TTS 时原样透传） */
    @Excel(name = "音色编码")
    private String voiceCode;

    /** 冗余：音色所属模型ID（aid_ai_model.id） */
    @Excel(name = "音色模型ID")
    private Long modelId;

    /** 冗余：音色所属服务商ID（aid_ai_provider.id） */
    @Excel(name = "音色服务商ID")
    private Long providerId;
    /** 冗余：音色展示名 */
    @Excel(name = "音色名")
    private String voiceName;

    /** 冗余：音色头像图 URL */
    @Excel(name = "音色头像")
    private String avatarUrl;

    /** 冗余：试听音频 URL */
    @Excel(name = "试听音频")
    private String sampleUrl;

    /** 冗余：试听文案 */
    @Excel(name = "试听文案")
    private String sampleText;

    /** 冗余：语言区域码（zh-CN / en-US / ...） */
    @Excel(name = "语言")
    private String language;

    /** 冗余：性别（female / male / neutral） */
    @Excel(name = "性别")
    private String gender;

    /** 冗余：年龄段（child / teen / young / adult / middle / elderly） */
    @Excel(name = "年龄段")
    private String ageRange;
    /** 覆盖语速（0.50~2.00）；null 走音色默认 */
    @Excel(name = "覆盖语速")
    private BigDecimal overrideSpeed;

    /** 覆盖音调（-12.00~12.00）；null 走音色默认 */
    @Excel(name = "覆盖音调")
    private BigDecimal overridePitch;

    /** 覆盖情感（供应商原生编码，须命中音色所属模型 capability_json.emotions 白名单） */
    @Excel(name = "覆盖情感")
    private String overrideEmotion;
    /** 状态：0启用 1停用 */
    @Excel(name = "状态")
    private String status;

    /** 删除标志：0存在 2删除 */
    private String delFlag;
}
