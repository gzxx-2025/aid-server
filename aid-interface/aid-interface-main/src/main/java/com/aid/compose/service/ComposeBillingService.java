package com.aid.compose.service;

import com.aid.compose.domain.ComposeBillingSnapshot;

/**
 * 合成阶梯计费服务（独立合成分支）。
 *
 * @author 视觉AID
 */
public interface ComposeBillingService {

    /**
     * 预冻结：按估算秒数 → ceil 分钟 × 档单价。
     *
     * @param userId           用户ID
     * @param estimatedSeconds 估算秒数
     * @param resolution       分辨率档
     * @param traceId          计费追踪ID
     * @return 计费快照（含冻结积分；frozenCredits=0 表示已跳过冻结）
     */
    ComposeBillingSnapshot freeze(Long userId, long estimatedSeconds, String resolution, String traceId);

    /**
     * 结算：按 MPS 实际输出秒数多退少补。
     *
     * @param userId        用户ID
     * @param actualSeconds 实际输出秒数
     * @param snapshot      预冻结时产出的计费快照
     * @param traceId       计费追踪ID
     */
    void settle(Long userId, long actualSeconds, ComposeBillingSnapshot snapshot, String traceId);

    /**
     * 失败全额退款。
     *
     * @param userId   用户ID
     * @param snapshot 预冻结时产出的计费快照
     * @param traceId  计费追踪ID
     */
    void refund(Long userId, ComposeBillingSnapshot snapshot, String traceId);
}
