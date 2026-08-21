package com.aid.media.service;

/**
 * 文本流式输出下沉接口：由 Controller 适配为 SSE，避免 interface 层依赖 Spring Web 类型。
 */
public interface MediaTextStreamSink {

    // 业务含义：任务已入库且扣费成功后下发平台 taskId，供客户端与查询接口对齐。
    void onTaskPrepared(long taskId);

    /** 已进入统一队列，当前流不会继续收到增量；调用方应结束 SSE 并改为查询任务。 */
    default void onDetached(long taskId) {}

    // 业务含义：上游返回的正文增量，原样转发给前端展示。
    void onDelta(String content);

    /** 上游公开的思考内容增量；调用方只可实时转发，不得持久化。 */
    default void onReasoningDelta(String content) {}

    // 业务含义：整段生成结束，附带全文与截断后的审计快照（可为空串）。
    void onDone(String fullText, String truncatedRawSnapshot);

    // 业务含义：业务失败（扣费失败在 prepare 前抛出；此处为生成或落库失败）。
    void onFailed(String userMessage);
}
