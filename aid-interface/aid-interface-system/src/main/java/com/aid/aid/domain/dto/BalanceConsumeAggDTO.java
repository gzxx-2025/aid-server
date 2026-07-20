package com.aid.aid.domain.dto;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

import lombok.Data;

/**
 * 余额变动按业务（related_id）聚合结果 DTO。
 *
 * @author 视觉AID
 */
@Data
public class BalanceConsumeAggDTO implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 关联业务ID（计费 traceId，串联同一任务的多行变动） */
    private String relatedId;

    /** 该业务最新一条变动记录的主键ID（用于排序，越大越新） */
    private Long lastId;

    /** 该业务最新一条变动记录的创建时间 */
    private Date createTime;

    /** 净增减 = SUM(after_balance - before_balance)，负数=净消耗，正数=净增加 */
    private BigDecimal changeAmount;

    /** 预冻结金额（freeze 行合计，正数），即本次业务最初冻结/预扣的积分 */
    private BigDecimal frozenAmount;

    /** 退款金额（所有正向返还合计：失败退回 / 差额退回等，正数） */
    private BigDecimal refundAmount;

    /** 超预扣补扣金额（settle_extra 合计，正数） */
    private BigDecimal extraAmount;

    /** originating 业务类型（取最早一行：create 创作 / extract 资产提取 / admin_adjust 等） */
    private String bizType;

    /** originating 业务名称（取最早一行，格式为“项目名：具体操作”） */
    private String bizName;

    /** 本次业务实际使用的模型编码，多个模型用英文逗号分隔 */
    private String modelCode;
}
