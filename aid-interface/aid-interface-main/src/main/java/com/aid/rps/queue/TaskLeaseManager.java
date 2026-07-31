package com.aid.rps.queue;

import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 执行租约管理器：任务 PROCESSING 期间写带实例 ID 的租约并定期续租，进程死亡后 TTL 到期消失，供重启自愈判定。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskLeaseManager
{
    /** 租约 TTL（秒）：90 秒，执行线程每 30 秒续租一次，三倍冗余防抖 */
    public static final long LEASE_TTL_SECONDS = 90L;

    /** 实例存活心跳 TTL（秒）：90 秒，心跳每 30 秒续一次，进程死亡后约 90 秒内失活 */
    public static final long INSTANCE_ALIVE_TTL_SECONDS = 90L;

    private final StringRedisTemplate stringRedisTemplate;

    /** 本实例唯一标识（进程级，重启即变） */
    private String instanceId;

    /** 本实例正在执行的任务ID集合（PROCESSING 期间登记，终态移除），用于定时心跳统一续租 */
    private final Set<Long> activeTaskIds = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void init()
    {
        // pid@host + 随机后缀，保证多实例（含同机多进程）唯一
        String jvmName = ManagementFactory.getRuntimeMXBean().getName();
        this.instanceId = jvmName + "#" + UUID.randomUUID().toString().substring(0, 8);
        log.info("任务执行实例ID初始化: {}", instanceId);
        // 启动即写一次实例存活心跳，保证本实例刚起来、首个定时心跳到来前就已"存活"，
        // 避免被其它实例的 owner 失活收口误判为已死。
        renewInstanceAlive();
    }

    public String getInstanceId()
    {
        return instanceId;
    }

    /** 登记任务为本实例执行中（进 PROCESSING 时调用），并立即写一次租约 */
    public void markActive(Long taskId)
    {
        if (taskId == null)
        {
            return;
        }
        activeTaskIds.add(taskId);
        renew(taskId);
    }

    /**
     * 仅续租一次，不登记到心跳常驻集合 {@link #activeTaskIds}（用于非阻塞扇入型父任务，靠 media 轮询续租）。
     */
    public void touchLease(Long taskId)
    {
        if (taskId == null)
        {
            return;
        }
        renew(taskId);
    }

    /**
     * 停止心跳常驻续租，但保留当前租约 key（不删除、保留剩余 TTL）。
     * 用于扇入型父任务「同步提交阶段」结束、转入异步在途时：同步阶段（如 Agnes 同步直出、单张可能
     * 耗时 &gt; 租约 TTL）先靠心跳续租防租约过期被僵尸回收误杀；转异步后交给 media 轮询续租，故从心跳集合
     * 移除，避免"按进程存活无限续租"导致名额永久泄漏；保留租约 key 给 media 轮询接管留出时间窗口。
     */
    public void deactivateHeartbeat(Long taskId)
    {
        if (taskId == null)
        {
            return;
        }
        activeTaskIds.remove(taskId);
    }

    /** 定时心跳：每 30 秒为本实例所有执行中任务续租，并续本实例存活心跳（由 Quartz 固定任务 systemCoreTask.leaseHeartbeat 触发） */
    public void heartbeat()
    {
        // 先续实例存活心跳（与有无执行中任务无关：排队中的 LOCAL 任务靠它判断 owner 死活）
        try
        {
            renewInstanceAlive();
        }
        catch (Exception e)
        {
            log.debug("续实例存活心跳异常(忽略): {}", e.getMessage());
        }
        if (activeTaskIds.isEmpty())
        {
            return;
        }
        for (Long taskId : activeTaskIds)
        {
            try
            {
                renew(taskId);
            }
            catch (Exception e)
            {
                log.debug("续租异常(忽略), taskId={}: {}", taskId, e.getMessage());
            }
        }
    }

    /** 续写本实例存活心跳 key（value=instanceId，TTL={@link #INSTANCE_ALIVE_TTL_SECONDS}） */
    private void renewInstanceAlive()
    {
        stringRedisTemplate.opsForValue().set(
                TaskQueueKeys.instanceAliveKey(instanceId), instanceId,
                INSTANCE_ALIVE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 指定实例是否存活（用于排队中 LOCAL 任务的 owner 死活判定）。
     *
     * @param instanceId 实例ID（取自 {@code QueuedTaskContext.ownerInstanceId}）
     * @return true=存活 / 无法判定（Redis 异常保守视为存活）；false=确已失活
     */
    public boolean isInstanceAlive(String instanceId)
    {
        if (instanceId == null || instanceId.isEmpty())
        {
            // 无 owner 信息（旧 ctx）→ 无法判定，保守视为存活，交由其它机制处理
            return true;
        }
        try
        {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(TaskQueueKeys.instanceAliveKey(instanceId)));
        }
        catch (Exception e)
        {
            // Redis 异常时偏保守：当作存活，避免误判 owner 已死而错误失败退款
            log.warn("查询实例存活心跳失败, 视为存活, instanceId={}", instanceId, e);
            return true;
        }
    }

    /** 写 / 续租任务执行租约（value=本实例ID，TTL=租约时长） */
    public void renew(Long taskId)
    {
        if (taskId == null)
        {
            return;
        }
        try
        {
            stringRedisTemplate.opsForValue().set(
                    TaskQueueKeys.leaseKey(taskId), instanceId, LEASE_TTL_SECONDS, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            log.warn("写执行租约失败, taskId={}", taskId, e);
        }
    }

    /** 租约是否存活（存在即视为有执行进程在跑） */
    public boolean isAlive(Long taskId)
    {
        if (taskId == null)
        {
            return false;
        }
        try
        {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(TaskQueueKeys.leaseKey(taskId)));
        }
        catch (Exception e)
        {
            // Redis 异常时偏保守：当作存活，避免误杀正在跑的任务
            log.warn("查询执行租约失败, 视为存活, taskId={}", taskId, e);
            return true;
        }
    }

    /** 释放租约（任务终态时调用） */
    public void release(Long taskId)
    {
        if (taskId == null)
        {
            return;
        }
        activeTaskIds.remove(taskId);
        try
        {
            stringRedisTemplate.delete(TaskQueueKeys.leaseKey(taskId));
        }
        catch (Exception e)
        {
            log.warn("释放执行租约失败, taskId={}", taskId, e);
        }
    }
}
