package com.aid.runner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.aid.common.aid.rocketmq.config.RocketMqConfigManager;

import lombok.extern.slf4j.Slf4j;

/**
 * 应用启动时强制从数据库 {@code aid_config(category=mq)} 重新加载 RocketMQ 开关配置。
 * <p>
 * 背景：{@link RocketMqConfigManager} 采用懒加载——首次调用 {@code isEnabled()} / {@code isRocketMq()}
 * 等方法时才 {@code refresh()} 读库，之后由 {@code initialized} 标记 + 内存 {@code configCache} 兜住，
 * 不再读库。这导致"启动时是否真的以数据库为准"取决于谁第一个触发懒加载、以及触发时机，存在不确定性。
 * </p>
 * <p>
 * 本 Runner 在容器就绪后<b>无条件调用一次 {@code refresh()}</b>，强制直读数据库覆盖内存配置，
 * 保证<b>每次程序启动 mqType / enabled 都以 aid_config 当前值为准，不依赖任何残留内存态或懒加载时机</b>。
 * 运行期的"刷新配置"按钮、懒加载、isEnabled() 判定等其余逻辑保持不变，本 Runner 只补齐"启动即直读 DB"。
 * </p>
 * <p>
 * 失败处理：refresh 异常不阻断应用启动（退回原懒加载兜底），仅打 error 日志便于排查，
 * 与 {@code ExtractZombieStartupRunner} 的"启动自愈失败不阻断启动"纪律一致。
 * </p>
 *
 * @author AID
 */
@Slf4j
@Component
@Order(1) // 尽早执行：MQ 开关需在任务派发 / 自愈等逻辑之前以 DB 为准
public class RocketMqConfigStartupRunner implements ApplicationRunner
{
    @Autowired
    private RocketMqConfigManager rocketMqConfigManager;

    @Override
    public void run(ApplicationArguments args)
    {
        try
        {
            // 无条件直读 DB 覆盖内存配置（refresh 内部 clear + 重新 putAll + 重置 initialized）
            rocketMqConfigManager.refresh();
            log.info("[MQ-STARTUP] RocketMQ 开关配置已在启动时从数据库强制加载: mqType={}, enabled={}",
                    rocketMqConfigManager.getMqType(), rocketMqConfigManager.isEnabled());
        }
        catch (Exception e)
        {
            // 启动期 DB 不可达等异常不阻断启动，退回懒加载兜底（首次使用时再读库）
            log.error("[MQ-STARTUP] 启动时强制加载 RocketMQ 开关配置失败（不阻断启动，退回懒加载兜底）", e);
        }
    }
}
