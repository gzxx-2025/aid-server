package com.aid.aid.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
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
 * 用户扩展信息对象 aid_user_profile
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_user_profile")
public class AidUserProfile extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID (关联sys_user.user_id) */
    @Excel(name = "用户ID (关联sys_user.user_id)")
    private Long userId;

    /** 账户余额 (元) */
    @Excel(name = "账户余额 (元)")
    private BigDecimal balance;

    /** 冻结余额 (元) */
    @Excel(name = "冻结余额 (元)")
    private BigDecimal frozenBalance;

    /** 是否实名认证 */
    @Excel(name = "是否实名认证")
    private String isReal;

    /** 真实姓名 */
    @Excel(name = "真实姓名")
    private String realName;

    /** 身份证 */
    @Excel(name = "身份证")
    private String idCard;

    /** 会员等级编码 (关联aid_member_level.level_code) */
    @Excel(name = "会员等级编码 (关联aid_member_level.level_code)")
    private String memberLevel;

    /** 会员到期时间 (NULL非会员) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "会员到期时间 (NULL非会员)", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date memberExpireTime;

    /** 累计充值金额 */
    @Excel(name = "累计充值金额")
    private BigDecimal totalRecharge;

    /** 累计消费金额 */
    @Excel(name = "累计消费金额")
    private BigDecimal totalConsumption;

    /** 微信模板消息推送开关 */
    private Integer wechatNotifyEnabled;

    /** 余额不足提醒资格：充值达标后置为1，成功提醒后置为0 */
    private Integer balanceReminderAvailable;

    /** 用户级作品发布权限：1允许 0禁止（发布总开关优先级更高，白名单用户不受总开关限制） */
    private Integer publishEnabled;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

}
