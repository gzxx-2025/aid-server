package com.aid.pay.controller;

import com.aid.common.annotation.Anonymous;
import com.aid.pay.service.IAidPayOrderBussinessService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * 微信支付回调Controller
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/pay/notify")
@RequiredArgsConstructor
public class WxpayNotifyController {

    private final IAidPayOrderBussinessService payOrderService;

    /**
     * 微信支付回调（V3 JSON 报文，免登录）。
     * 按 HTTP 状态码应答微信：处理成功回 200，否则回 5XX 触发微信按既定频次重试，避免"处理失败但回 200"导致丢单。
     *
     * @param request HTTP请求（携带微信验签头）
     * @param body    回调请求体（JSON 密文报文）
     * @return 应答结果（code=SUCCESS/FAIL）
     */
    @Anonymous
    @PostMapping("/wxpay")
    public ResponseEntity<Map<String, Object>> wxpayNotify(HttpServletRequest request, @RequestBody String body) {
        log.info("收到微信支付回调通知");

        String serial = request.getHeader("Wechatpay-Serial");
        String nonce = request.getHeader("Wechatpay-Nonce");
        String timestamp = request.getHeader("Wechatpay-Timestamp");
        String signature = request.getHeader("Wechatpay-Signature");

        log.info("微信回调头信息: serial={}, nonce={}, timestamp={}", serial, nonce, timestamp);

        Map<String, Object> result = payOrderService.handleWxpayNotify(serial, nonce, timestamp, signature, body);

        // 仅当业务处理成功（code=SUCCESS）才回 200；否则回 5XX 触发微信重试
        boolean success = Objects.nonNull(result) && "SUCCESS".equals(result.get("code"));
        if (success) {
            return ResponseEntity.ok(result);
        }
        log.warn("微信支付回调处理未成功，返回 5XX 以触发微信重试: {}", result);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
    }
}
