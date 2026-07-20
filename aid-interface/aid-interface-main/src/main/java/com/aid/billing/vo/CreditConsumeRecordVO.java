package com.aid.billing.vo;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 积分消耗明细 VO（C 端，按业务/任务聚合后的一条记录）。
 * 一个生成任务（图片/视频/配音/资产提取/分镜续生等）可能经历「预冻结 → 结算扣费 → 部分退款 / 超额补扣」，
 * 本 VO 把同一任务的多笔变动聚合为一条，直观展示：花了多少、退了多少、最终净消耗多少。
 * 金额单位为Credits。
 *
 * @author 视觉AID
 */
@Data
public class CreditConsumeRecordVO implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务流水号（计费 traceId，串联同一任务的多笔变动；可用于客服核账） */
    private String bizTraceId;

    /** 业务类型（原始）：create 创作 / extract 资产提取 / admin_adjust 管理员调整 等 */
    private String bizType;

    /**
     * 业务类型中文名（什么步骤消耗的，如「媒体创作」「资产提取」）。
     */
    private String bizTypeName;

    /** 明确业务名称，格式为“项目名：具体操作” */
    private String bizName;

    /** 本次业务实际使用的模型展示名称，多个模型用顿号分隔 */
    private String modelName;

    /**
     * 积分增减（净额，带符号）：负数=本业务净消耗，正数=净增加。
     * 恒等于该业务对可用余额的真实净影响。
     */
    private BigDecimal changeAmount;

    /** 实际消耗（正数）：净消耗金额，= -changeAmount（净额为正时为 0） */
    private BigDecimal consumedAmount;

    /** 最初冻结/预扣金额（正数）：退款前「花了多少」 */
    private BigDecimal frozenAmount;

    /** 是否发生退款（失败全额退回 / 生成部分后差额退回） */
    private Boolean hasRefund;

    /** 退款金额（正数）：本业务累计退回多少积分，无退款为 0 */
    private BigDecimal refundAmount;

    /** 超预扣补扣金额（正数）：实际用量超过预扣时的补扣，无则为 0 */
    private BigDecimal extraAmount;

    /** 发生时间（该业务最新一笔变动时间）。历史数据可能为 null */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
