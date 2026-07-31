package com.aid.aid.domain.vo;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;

/**
 * 用户扩展信息列表VO
 * 聚合 aid_user_profile（账户/会员/实名）与 sys_user（昵称/手机号/状态等基础信息），
 * 供后台"用户管理"列表与详情展示。同时复用作为列表查询条件载体（昵称/手机号/状态模糊查）。
 *
 * @author 视觉AID
 */
@Data
public class AidUserProfileVo
{
    /** 扩展信息主键ID */
    private Long id;

    /** 用户ID (关联sys_user.user_id) */
    private Long userId;

    /** 账户余额 (元) */
    private BigDecimal balance;

    /** 冻结余额 (元) */
    private BigDecimal frozenBalance;

    /** 是否实名认证 */
    private String isReal;

    /** 真实姓名 */
    private String realName;

    /** 身份证 */
    private String idCard;

    /** 会员等级编码 */
    private String memberLevel;

    /** 会员到期时间 (NULL非会员) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date memberExpireTime;

    /** 累计充值金额 */
    private BigDecimal totalRecharge;

    /** 累计消费金额 */
    private BigDecimal totalConsumption;

    /** 备注 */
    private String remark;

    /** 扩展信息创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    /** 用户账号 */
    private String userName;

    /** 用户昵称 */
    private String nickName;

    /** 头像地址 (相对路径，出参拼域名) */
    @MediaUrl
    private String avatar;

    /** 手机号码 */
    private String phonenumber;

    /** 用户邮箱 */
    private String email;

    /** 用户性别 (0男 1女 2未知) */
    private String sex;

    /** 账号状态 (0正常 1停用) */
    private String status;

    /** 最后登录IP */
    private String loginIp;

    /** 最后登录时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date loginDate;

    /** 注册时间 (sys_user.create_time) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date registerTime;
}
