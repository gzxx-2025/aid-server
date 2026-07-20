package com.aid.media.enums;

public enum MediaTaskStatus {
    // 任务已创建，尚未进入供应商处理。
    PENDING,
    // 任务排队等待中：并发数已满，等待前面的任务完成后自动提交。
    QUEUED,
    // 任务已提交到供应商，等待轮询完成（旧状态，兼容已有任务）。
    PROCESSING,
    // 等待回调通知（回调优先模式）。
    WAIT_CALLBACK,
    // 等待调度中心轮询（纯轮询或回调超时后）。
    WAIT_POLL,
    // 任务处理成功。
    SUCCEEDED,
    // 任务处理失败。
    FAILED
}
