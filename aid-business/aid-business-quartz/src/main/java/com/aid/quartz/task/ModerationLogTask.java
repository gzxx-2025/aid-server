package com.aid.quartz.task;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aid.aid.service.IAidImageModerationLogService;

import lombok.extern.slf4j.Slf4j;

/**
 * 图片审查日志清理 - 定时任务（可选任务，允许用户开关）
 *
 * 删除超过配置保留天数(logRetentionDays)的图片审查日志，防止日志表无限增长。
 * 清理失败仅记日志，不影响业务。
 *
 * 调用示例（后台任务管理配置）：
 *   目标字符串：moderationLogTask.cleanExpired()
 *
 * @author AID
 */
@Slf4j
@Component("moderationLogTask")
public class ModerationLogTask {

    @Autowired
    private IAidImageModerationLogService aidImageModerationLogService;

    /** 防重入标记 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 清理过期图片审查日志
     */
    public void cleanExpired() {
        if (!running.compareAndSet(false, true)) {
            log.debug("图片审查日志清理上一轮仍在执行，跳过本次触发");
            return;
        }
        try {
            int count = aidImageModerationLogService.cleanExpired();
            if (count > 0) {
                log.info("清理过期图片审查日志完成, 删除数量={}", count);
            }
        } catch (Exception e) {
            // 定时清理失败不影响业务，仅记日志
            log.error("清理过期图片审查日志异常", e);
        } finally {
            running.set(false);
        }
    }
}
