package com.aid.quartz.task;

import com.aid.providerbalance.service.ProviderBalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/** 供应商余额监控调度器。 */
@Slf4j
@Component("providerBalanceTask")
@RequiredArgsConstructor
public class ProviderBalanceTask {
    private static final String LOCK_KEY = "provider_balance:task:lock";

    private final ProviderBalanceService providerBalanceService;
    private final RedissonClient redissonClient;

    public void tick() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock();
            if (!acquired) return;
            providerBalanceService.tick();
        } catch (Exception ex) {
            log.error("供应商余额监控任务执行失败", ex);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
