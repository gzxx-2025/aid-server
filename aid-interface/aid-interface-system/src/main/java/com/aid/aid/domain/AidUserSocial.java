package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * 用户第三方登录授权对象 aid_user_social
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_user_social")
public class AidUserSocial extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 平台类型 */
    @Excel(name = "平台类型")
    private String platformSource;

    /** 三方平台唯一标识 */
    @Excel(name = "三方平台唯一标识")
    private String openid;

    /** 三方平台全平台唯一标识 (如微信的unionid) */
    @Excel(name = "三方平台全平台唯一标识 (如微信的unionid)")
    private String unionid;

    /** 三方平台返回的原始数据报文 */
    @Excel(name = "三方平台返回的原始数据报文")
    private String rawData;

    /** 删除标志 */
    private String delFlag;

}
