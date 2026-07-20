package com.aid.quartz.task;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aid.modelhealth.service.ModelHealthArchiveService;

import lombok.extern.slf4j.Slf4j;

/**
 * 模型健康统计归档 - 定时任务。
 *
 * <p>把 aid_model_health_stat 中早于保留天数的统计行导出为服务器本地 txt 存档
 * （可丢失的运维留档）后从库删除，保证统计表体量恒定。建议每日凌晨执行一次。</p>
 *
 * 调用示例（后台「定时任务」中配置目标字符串）：
 *   modelHealthTask.archive()      -- 保留最近30天
 *   modelHealthTask.archive(3)     -- 自定义保留天数
 *
 * @author AID
 */
@Slf4j
@Component("modelHealthTask")
public class ModelHealthTask {

    /** 默认保留天数（监控页最长统计窗口为30天） */
    private static final int DEFAULT_KEEP_DAYS = 30;

    @Autowired
    private ModelHealthArchiveService modelHealthArchiveService;

    /** 防重入标记 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 归档模型健康统计，默认保留最近30天 */
    public void archive() {
        archive(DEFAULT_KEEP_DAYS);
    }

    /** 归档模型健康统计，自定义保留天数 */
    public void archive(Integer keepDays) {
        if (!running.compareAndSet(false, true)) {
            log.debug("模型健康统计归档上一轮仍在执行，跳过本次触发");
            return;
        }
        try {
            int days = keepDays != null && keepDays > 0 ? keepDays : DEFAULT_KEEP_DAYS;
            int archived = modelHealthArchiveService.archiveAndCleanup(days);
            if (archived > 0) {
                log.info("模型健康统计归档完成, keepDays={}, archived={}", days, archived);
            }
        } finally {
            running.set(false);
        }
    }
}
