package com.aid.aid.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.aid.monitor.ModelQueueMonitorService;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;

import lombok.RequiredArgsConstructor;

/**
 * AI 模型排队 / 并发实时监控接口（后台只读）。
 * 提供全局 / 服务商 / 模型 三层的实时并发占用、排队条数与使用频繁度。
 * 数据来自 {@link ModelQueueMonitorService} 的短 TTL 缓存快照——前端可放心高频轮询，
 * 不会对线上业务的 Redis 调度与业务表造成额外压力。
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/modelmonitor")
@RequiredArgsConstructor
public class ModelQueueMonitorController extends BaseController
{
    private final ModelQueueMonitorService modelQueueMonitorService;

    /**
     * 获取模型排队 / 并发实时监控快照。
     * 前端轮询此接口实现页面同步更新；服务端已做缓存合并，多端轮询零放大。
     */
    @PreAuthorize("@ss.hasPermi('aid:modelmonitor:list')")
    @GetMapping("/snapshot")
    public AjaxResult snapshot()
    {
        return success(modelQueueMonitorService.getSnapshot());
    }
}
