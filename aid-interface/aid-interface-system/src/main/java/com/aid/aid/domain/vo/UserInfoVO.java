package com.aid.aid.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 用户信息响应对象
 *
 * @author 视觉AID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoVO {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String userName;

    /**
     * 昵称
     */
    private String nickName;

    /**
     * 头像（相对路径，出参拼域名）
     */
    @MediaUrl
    private String avatar;

    /**
     * 手机号
     */
    private String phonenumber;

    /**
     * 邮箱
     */
    private String email;
    /**
     * 账户余额 (元)
     */
    private BigDecimal balance;

    /**
     * 冻结余额 (元)
     */
    private BigDecimal frozenBalance;

    /**
     * 会员等级编码
     */
    private String memberLevel;

    /**
     * 会员到期时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date memberExpireTime;

    /**
     * 累计充值金额
     */
    private BigDecimal totalRecharge;

    /**
     * 累计消费金额
     */
    private BigDecimal totalConsumption;
    /**
     * 是否实名认证
     */
    private Boolean isReal;

    /**
     * 真实姓名
     */
    private String realName;

    /**
     * 身份证号
     */
    private String idCard;

    /**
     * 微信模板消息推送开关
     */
    private Boolean wechatNotifyEnabled;
}
