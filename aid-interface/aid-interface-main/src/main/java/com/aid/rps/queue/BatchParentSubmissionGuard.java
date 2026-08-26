package com.aid.rps.queue;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.core.util.StrUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 串行化批量父任务的子任务提交与取消收口。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchParentSubmissionGuard
{
    private static final String LOCK_PREFIX = "batch:parent:submission:";
    private static final long WAIT_SECONDS = 10L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final java.util.Set<String> ACTIVE_STATUSES = java.util.Set.of(
            "PENDING", "QUEUED", "PROCESSING", "FINALIZING", "RECOVERING");

    private final RedissonClient redissonClient;
    private final IAidExtractTaskService extractTaskService;
    private final TaskCancelFlagManager taskCancelFlagManager;
    private final BatchTaskSlotService batchTaskSlotService;

    public <T> T execute(Long parentTaskId, Supplier<T> action)
    {
        if (parentTaskId == null)
        {
            throw new ServiceException("任务不可用");
        }
        return execute("parent:" + parentTaskId, action);
    }

    public <T> T execute(String scope, Supplier<T> action)
    {
        if (StrUtil.isBlank(scope) || action == null)
        {
            throw new ServiceException("任务不可用");
        }
        RLock lock;
        try
        {
            lock = redissonClient.getLock(LOCK_PREFIX + scope);
        }
        catch (Exception ex)
        {
            log.error("父任务提交锁初始化异常: scope={}", scope, ex);
            throw new ServiceException("任务繁忙，请稍后");
        }
        boolean acquired;
        try
        {
            acquired = lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("任务繁忙，请稍后");
        }
        catch (Exception ex)
        {
            log.error("父任务提交锁获取异常: scope={}", scope, ex);
            throw new ServiceException("任务繁忙，请稍后");
        }
        if (!acquired)
        {
            throw new ServiceException("任务繁忙，请稍后");
        }
        try
        {
            return action.get();
        }
        finally
        {
            try
            {
                if (lock.isHeldByCurrentThread())
                {
                    lock.unlock();
                }
            }
            catch (Exception ex)
            {
                log.warn("父任务提交锁释放异常: scope={}, err={}", scope, ex.getMessage());
            }
        }
    }

    /** 仅对逻辑批量父任务串行复核执行周期后提交，普通单条任务保持原有粒度。 */
    public <T> T executeManagedTask(Long parentTaskId, String expectedTraceId, Supplier<T> action)
    {
        return executeManagedTask(parentTaskId, expectedTraceId, action,
                BatchTaskExecutionRejectedException::new);
    }

    /** 仅对逻辑批量父任务串行复核执行周期，调用方可保留原有受控拒绝异常。 */
    public <T> T executeManagedTask(Long parentTaskId, String expectedTraceId, Supplier<T> action,
                                    Supplier<? extends RuntimeException> rejectionFactory)
    {
        if (parentTaskId == null || action == null || rejectionFactory == null)
        {
            throw new ServiceException("任务不可用");
        }
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(parentTaskId);
        if (Objects.isNull(task))
        {
            throw rejectionFactory.get();
        }
        if (!batchTaskSlotService.isManagedBatchTask(task))
        {
            return action.get();
        }
        return execute(parentTaskId, () ->
        {
            requireActiveExecutionCycle(parentTaskId, expectedTraceId, rejectionFactory);
            T result = action.get();
            // 取消方先写 flag 再等待同一 watchdog 锁；调用返回后仍须在锁内复核，
            // 防止已受理结果在“停止成功”之后继续写入业务父任务或业务产物。
            requireActiveExecutionCycle(parentTaskId, expectedTraceId, rejectionFactory);
            return result;
        });
    }

    /** 在取消共用锁内复核执行周期后提交业务结果；允许 action 自身把父任务推进为终态。 */
    public <T> T executeManagedBusinessCommit(Long parentTaskId, String expectedTraceId, Supplier<T> action)
    {
        return executeManagedBusinessCommit(parentTaskId, expectedTraceId, action,
                BatchTaskExecutionRejectedException::new);
    }

    /** 在取消共用锁内复核执行周期后提交业务结果，并保留调用方受控拒绝异常。 */
    public <T> T executeManagedBusinessCommit(Long parentTaskId, String expectedTraceId, Supplier<T> action,
                                              Supplier<? extends RuntimeException> rejectionFactory)
    {
        if (parentTaskId == null || action == null || rejectionFactory == null)
        {
            throw new ServiceException("任务不可用");
        }
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(parentTaskId);
        if (Objects.isNull(task))
        {
            throw rejectionFactory.get();
        }
        if (!batchTaskSlotService.isManagedBatchTask(task))
        {
            return action.get();
        }
        return execute(parentTaskId, () ->
        {
            requireBusinessCommitCycle(parentTaskId, expectedTraceId, rejectionFactory);
            return action.get();
        });
    }

    private void requireBusinessCommitCycle(Long parentTaskId, String expectedTraceId,
                                            Supplier<? extends RuntimeException> rejectionFactory)
    {
        AidExtractTask current = extractTaskService.selectAidExtractTaskById(parentTaskId);
        if (Objects.isNull(current)
                || !batchTaskSlotService.isManagedBatchTask(current)
                || !ACTIVE_STATUSES.contains(current.getStatus())
                || taskCancelFlagManager.isCancelled(parentTaskId))
        {
            throw rejectionFactory.get();
        }
        if (StrUtil.isNotBlank(expectedTraceId))
        {
            if (!Objects.equals(expectedTraceId, current.getBillingTraceId()))
            {
                throw rejectionFactory.get();
            }
            return;
        }
        if (StrUtil.isBlank(current.getBillingTraceId())
                || !isConfirmedLegacySnapshot(current.getInputSnapshot()))
        {
            throw rejectionFactory.get();
        }
    }

    private boolean isConfirmedLegacySnapshot(String inputSnapshot)
    {
        if (StrUtil.isBlank(inputSnapshot))
        {
            return false;
        }
        try
        {
            JsonNode snapshot = OBJECT_MAPPER.readTree(inputSnapshot);
            return snapshot.isObject() && !snapshot.has("logicalBatchSlots");
        }
        catch (Exception ex)
        {
            return false;
        }
    }

    private void requireActiveExecutionCycle(Long parentTaskId, String expectedTraceId,
                                             Supplier<? extends RuntimeException> rejectionFactory)
    {
        AidExtractTask current = extractTaskService.selectAidExtractTaskById(parentTaskId);
        if (Objects.isNull(current)
                || !batchTaskSlotService.isManagedBatchTask(current)
                || !ACTIVE_STATUSES.contains(current.getStatus())
                || !Objects.equals(expectedTraceId, current.getBillingTraceId())
                || taskCancelFlagManager.isCancelled(parentTaskId))
        {
            throw rejectionFactory.get();
        }
    }
}
