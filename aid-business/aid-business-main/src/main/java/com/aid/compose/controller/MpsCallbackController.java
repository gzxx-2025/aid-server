package com.aid.compose.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.annotation.Anonymous;
import com.aid.common.aid.crypto.annotation.CryptoIgnore;
import com.aid.common.core.domain.AjaxResult;
import com.aid.compose.service.MpsCallbackService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 腾讯云 MPS 任务回调接收端点。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/media/callback")
@RequiredArgsConstructor
public class MpsCallbackController {

    /** MPS 回调处理服务（定位任务、来源校验、反查终态、防重放、统一收口） */
    private final MpsCallbackService mpsCallbackService;

    /**
     * 接收 MPS 回调。URL：POST /api/media/callback/mps
     * 标注 @CryptoIgnore：回调为上游明文 JSON，不参与本平台 API 加解密。
     * 处理异常由 Service 内部吞掉（依赖轮询兜底），一律 ACK 成功避免上游重试。
     *
     * @param rawBody     回调原始报文（可能为空）
     * @param httpRequest 原始请求（预留取头能力）
     * @return 一律 ACK 成功
     */
    @Anonymous
    @CryptoIgnore
    @PostMapping("/mps")
    public AjaxResult onMpsCallback(@RequestBody(required = false) String rawBody, HttpServletRequest httpRequest) {
        mpsCallbackService.handleMpsCallback(rawBody);
        return AjaxResult.success();
    }
}
