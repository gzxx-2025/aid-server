package com.aid.media.enums;

/**
 * 任务调度模式：决定任务提交后由哪种机制驱动到终态。
 */
public enum DispatchMode {

    /** 同步直返：流式文本、同步图片，不走调度中心 */
    DIRECT,

    /** 回调优先：先等上游回调通知，超时后转轮询兜底 */
    CALLBACK_FIRST,

    /** 纯轮询：不支持回调的供应商，完全由调度中心驱动 */
    POLL_ONLY
}
