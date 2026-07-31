package com.aid.aid.domain.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 管理员余额调整请求对象
 * 后台对指定用户的账户余额做手动增减（充值赠送 / 扣回等运营场景）。
 * 调整动作统一走计费模块的账户变更执行器，自动写入余额变动流水便于审计。
 *
 * @author 视觉AID
 */
@Data
public class BalanceAdjustDto
{
    /** 目标用户ID（必填） */
    private Long userId;

    /** 调整金额（必填，正数，单位：元；方向由 adjustType 决定） */
    private BigDecimal amount;

    /** 调整方向（必填）：add=增加余额 / deduct=扣减余额 */
    private String adjustType;

    /** 调整原因（写入余额流水的业务名称，便于审计追溯） */
    private String reason;
}
