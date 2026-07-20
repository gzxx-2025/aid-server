package com.aid.rps.queue;

/**
 * 任务排队 / 多维并发调度 Redis Key 常量，统一前缀 {@code taskq:}，用 StringRedisTemplate 读写。
 *
 * @author 视觉AID
 */
public final class TaskQueueKeys
{
    private TaskQueueKeys() {}

    /** 等待队列 ZSet：member=taskId, score=排队基准分（越小越靠前） */
    public static final String WAIT_ZSET = "taskq:wait";

    /** 任务上下文（JSON 字符串）：taskq:ctx:{taskId} */
    public static final String CTX_PREFIX = "taskq:ctx:";

    /** 调度分布式锁（多实例防重派发）：taskq:dlock:{taskId} */
    public static final String DISPATCH_LOCK_PREFIX = "taskq:dlock:";

    /** 执行租约（心跳续租，缺失即判定执行进程已死）：taskq:lease:{taskId}=instanceId */
    public static final String LEASE_PREFIX = "taskq:lease:";

    /** 实例存活心跳（实例级，缺失即判定该实例已死）：taskq:instance:alive:{instanceId} */
    public static final String INSTANCE_ALIVE_PREFIX = "taskq:instance:alive:";

    /** 队列层「取消请求」标记：taskq:cancelreq:{taskId}，调度器放行前检查命中则队列层取消 */
    public static final String CANCEL_REQ_PREFIX = "taskq:cancelreq:";

    // 四维并发占用集合（member=taskId, score=占用时间毫秒）

    /** 全局并发占用集合 */
    public static final String OCC_GLOBAL = "taskq:occ:global";
    /** 用户并发占用集合前缀：taskq:occ:user:{userId} */
    public static final String OCC_USER_PREFIX = "taskq:occ:user:";
    /** 模型并发占用集合前缀：taskq:occ:model:{modelCode} */
    public static final String OCC_MODEL_PREFIX = "taskq:occ:model:";
    /** 服务商并发占用集合前缀：taskq:occ:provider:{providerId} */
    public static final String OCC_PROVIDER_PREFIX = "taskq:occ:provider:";

    /** 服务商维度缺省占位（providerId 为空时统一落此桶，limit<=0 不限） */
    public static final String PROVIDER_NONE = "none";

    public static String ctxKey(Long taskId)
    {
        return CTX_PREFIX + taskId;
    }

    public static String dispatchLockKey(Long taskId)
    {
        return DISPATCH_LOCK_PREFIX + taskId;
    }

    public static String leaseKey(Long taskId)
    {
        return LEASE_PREFIX + taskId;
    }

    public static String instanceAliveKey(String instanceId)
    {
        return INSTANCE_ALIVE_PREFIX + instanceId;
    }

    public static String cancelReqKey(Long taskId)
    {
        return CANCEL_REQ_PREFIX + taskId;
    }

    public static String userOccKey(Long userId)
    {
        return OCC_USER_PREFIX + (userId == null ? "anonymous" : userId);
    }

    public static String modelOccKey(String modelCode)
    {
        return OCC_MODEL_PREFIX + (modelCode == null || modelCode.isEmpty() ? "unknown" : modelCode);
    }

    public static String providerOccKey(Long providerId)
    {
        return OCC_PROVIDER_PREFIX + (providerId == null ? PROVIDER_NONE : providerId);
    }
}
