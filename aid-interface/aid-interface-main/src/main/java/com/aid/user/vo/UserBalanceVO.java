package com.aid.user.vo;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * C 端用户账户积分响应对象。
 *
 * @author 视觉AID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBalanceVO {

    /** 可用积分 */
    private BigDecimal balance;

    /** 冻结积分 */
    private BigDecimal frozenBalance;

    /** 累计充值积分 */
    private BigDecimal totalRecharge;

    /** 累计消费积分 */
    private BigDecimal totalConsumption;
}
