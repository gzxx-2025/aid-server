package com.aid.aid.service;

import com.aid.aid.domain.AidImageModerationLog;

/**
 * 图片内容审核日志Service接口
 *
 * @author 视觉AID
 */
public interface IAidImageModerationLogService
{
    /**
     * 记录一条图片审查日志
     * - 仅在 status∈{BLOCK,REVIEW,ERROR} 时写库；若配置 logPassed=true 则 PASS 也写
     * - 内部容错：写库失败仅记录日志，不向上抛出，避免影响主链路
     *
     * @param log 审查日志对象
     */
    void record(AidImageModerationLog log);

    /**
     * 清理过期审查日志：删除创建时间早于「当前时间 - 配置保留天数(logRetentionDays)」的记录
     * - 分批删除（每批上限），避免大表单次删除锁表过久
     * - 由定时任务每日触发，内部容错，失败仅记日志
     *
     * @return 本次清理的记录数
     */
    int cleanExpired();
}
