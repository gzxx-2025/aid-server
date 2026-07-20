package com.aid.rps.queue;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 等待队列分维度统计快照（只读，供后台模型排队监控用，不参与调度写路径）。
 *
 * @author 视觉AID
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueueWaitingBreakdown
{
    /** 等待队列总长度（ZCARD，与扫描上限无关，反映真实排队规模） */
    private long totalWaiting;

    /** 本次 range 取回的 member 条数（= min(totalWaiting, scanLimit) 的瞬时快照，用于判定扫描窗口是否打满） */
    private int membersSize;

    /** 本次实际成功解析 ctx 的排队条数（<= membersSize；member 存在但 ctx 缺失/过期时会小于 membersSize） */
    private int scanned;

    /** 按模型编码统计的排队条数：modelCode -> count */
    private Map<String, Integer> waitingByModel;

    /** 按服务商ID统计的排队条数：providerId -> count（providerId 可能为 null，对应 key="none"） */
    private Map<String, Integer> waitingByProvider;
}
