package com.aid.compose.service;

/**
 * 腾讯云 MPS 任务回调处理服务
 *
 * @author 视觉AID
 */
public interface MpsCallbackService {

    /**
     * 处理 MPS 回调报文：定位任务 → 来源自洽校验 → 反查真实终态 → 防重放 → 统一终态收口。
     * 任一异常内部吞掉（依赖轮询兜底），调用方一律 ACK 成功避免上游无意义重试。
     *
     * @param rawBody 回调原始报文（可能为空）
     */
    void handleMpsCallback(String rawBody);
}
