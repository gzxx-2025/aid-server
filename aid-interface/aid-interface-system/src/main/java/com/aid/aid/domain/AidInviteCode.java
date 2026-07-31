package com.aid.aid.domain;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.core.domain.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 用户邀请码对象 aid_invite_code
 * 每个用户一个全局唯一邀请码，首次进入邀请页时懒生成。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_invite_code")
public class AidInviteCode extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID (关联sys_user.user_id，全局唯一) */
    private Long userId;

    /** 邀请码（大写字母+数字，去除易混淆字符，全局唯一） */
    private String inviteCode;

    /** 删除标志（0存在 1删除） */
    private String delFlag;
}
