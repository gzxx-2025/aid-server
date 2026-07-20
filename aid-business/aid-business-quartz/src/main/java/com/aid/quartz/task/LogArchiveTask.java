package com.aid.quartz.task;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aid.system.service.ILogArchiveService;

import lombok.extern.slf4j.Slf4j;

/**
 * 日志归档 - 定时任务（需求6）。
 *
 * <p>把数据库中早于保留天数的「操作日志 / 登录日志」归档为本地文件后删除，
 * 控制日志表体量。建议每日凌晨执行一次，并设置「禁止并发」。</p>
 *
 * 调用示例（后台「定时任务」中配置目标字符串）：
 *   logArchiveTask.archive()          -- 操作日志、登录日志均保留最近10天
 *   logArchiveTask.archive(7)         -- 自定义保留天数
 *   logArchiveTask.archiveOper(10)    -- 仅归档操作日志
 *   logArchiveTask.archiveLogin(10)   -- 仅归档登录日志
 *
 * @author AID
 */
@Slf4j
@Component("logArchiveTask")
public class LogArchiveTask {

    /** 默认保留天数 */
    private static final int DEFAULT_KEEP_DAYS = 10;

    @Autowired
    private ILogArchiveService logArchiveService;

    /** 防重入标记 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 归档操作日志 + 登录日志，默认保留最近10天 */
    public void archive() {
        archive(DEFAULT_KEEP_DAYS);
    }

    /** 归档操作日志 + 登录日志，自定义保留天数 */
    public void archive(Integer keepDays) {
        if (!running.compareAndSet(false, true)) {
            log.debug("日志归档上一轮仍在执行，跳过本次触发");
            return;
        }
        try {
            int days = keepDays != null && keepDays > 0 ? keepDays : DEFAULT_KEEP_DAYS;
            int oper = logArchiveService.archiveOperLog(days);
            int login = logArchiveService.archiveLogininfor(days);
            if (oper > 0 || login > 0) {
                log.info("日志归档完成, keepDays={}, operLog={}, loginLog={}", days, oper, login);
            }
        } finally {
            running.set(false);
        }
    }

    /** 仅归档操作日志 */
    public void archiveOper(Integer keepDays) {
        int days = keepDays != null && keepDays > 0 ? keepDays : DEFAULT_KEEP_DAYS;
        logArchiveService.archiveOperLog(days);
    }

    /** 仅归档登录日志 */
    public void archiveLogin(Integer keepDays) {
        int days = keepDays != null && keepDays > 0 ? keepDays : DEFAULT_KEEP_DAYS;
        logArchiveService.archiveLogininfor(days);
    }
}
