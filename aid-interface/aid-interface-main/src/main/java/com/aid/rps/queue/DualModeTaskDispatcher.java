package com.aid.rps.queue;

import org.springframework.stereotype.Component;

import com.aid.common.aid.rocketmq.config.RocketMqConfigManager;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 任务派发模式统一路由器（MQ / 本地双模式切换的唯一收口），读开关异常时安全降级为本地模式。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class DualModeTaskDispatcher
{
    @Resource
    private TaskQueueService taskQueueService;

    @Resource
    private RocketMqConfigManager rocketMqConfigManager;

    /** 当前是否启用 MQ 派发；读取异常按本地模式降级 */
    public boolean isMqEnabled()
    {
        try
        {
            return rocketMqConfigManager.isEnabled();
        }
        catch (Exception e)
        {
            log.warn("读取MQ配置失败，默认走本地模式: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 双模式入队派发。
     *
     * @param taskId    父任务 ID
     * @param projectId 项目 ID
     * @param episodeId 剧集 ID
     * @param userId    用户 ID
     * @param modelCode 模型编码（并发名额按服务商维度解析用）
     * @param taskType  任务类型
     * @param localJob  本地模式下的执行体（MQ 模式忽略）；与 MQ Consumer 共用同一终态编排时由调用方传入
     * @return true=入队成功；false=CAS 失败（任务已被推进/取消），由调用方决定记日志或回滚
     */
    public boolean dispatch(Long taskId, Long projectId, Long episodeId, Long userId,
                            String modelCode, String taskType, Runnable localJob)
    {
        if (isMqEnabled())
        {
            return taskQueueService.submitMqTask(taskId, projectId, episodeId, userId, modelCode, taskType);
        }
        return taskQueueService.submitLocalTask(taskId, projectId, episodeId, userId, modelCode, taskType, localJob);
    }
}
