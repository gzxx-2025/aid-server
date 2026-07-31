package com.aid.media.provider;

/**
 * 文本上游流式回调：由 {@link TextProviderClient#streamChat} 在解析 SSE 时驱动，编排层可转发到 SSE 或聚合为整段。
 */
public interface TextStreamCallbacks {

    // 业务含义：模型输出的可见正文增量片段（多段拼接即为完整回复）。
    void onDelta(String textDelta);

    // 业务含义：单条 SSE data 行原文（不含 "data: " 前缀），用于审计或排障时落库截断快照。
    void onSseDataLine(String dataLine);

    // 业务含义：流式链路异常（HTTP 非 200、JSON 解析失败、上游业务错误体等）。
    void onError(String message, Throwable cause);

    // 业务含义：上游正常结束（读到 [DONE] 或流关闭且无未处理错误）。
    void onComplete();

    /**
     * 上游返回的 token usage 数据（SSE 最后一个 chunk 中的 usage 对象）。
     * 默认空实现，实现类按需覆写。
     */
    default void onUsage(java.util.Map<String, Object> usage) {}
}
