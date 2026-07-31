package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * 用户自定义AI大模型配置(配置覆盖用)对象 aid_user_ai_config
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
// 修复 SC8：toString 不输出 customApiKey / customApiSecret，避免对象被打印时泄漏
@ToString(callSuper = true, exclude = {"customApiKey", "customApiSecret"})
@TableName(value = "aid_user_ai_config")
public class AidUserAiConfig extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属用户ID */
    @Excel(name = "所属用户ID")
    private Long userId;

    /** 要覆盖的服务商ID (关联 aid_ai_provider.id) */
    @Excel(name = "要覆盖的服务商ID (关联 aid_ai_provider.id)")
    private Long providerId;

    /** 用户自定义代理网关 (为空则走官方网关) */
    @Excel(name = "用户自定义代理网关 (为空则走官方网关)")
    private String customBaseUrl;

    /** 用户自带API秘钥 (加密存储) */
    /**
     * 修复 SC8: 使用 WRITE_ONLY 防止密钥被返回给前端明文展示
     */
    @Excel(name = "用户自带API秘钥 (加密存储)")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String customApiKey;

    /** 用户自带扩展秘钥 (如需) */
    @Excel(name = "用户自带扩展秘钥 (如需)")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String customApiSecret;

    /** 是否启用自带配置 (0启用 1停用) */
    @Excel(name = "是否启用自带配置 (0启用 1停用)")
    private String isEnable;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

}
