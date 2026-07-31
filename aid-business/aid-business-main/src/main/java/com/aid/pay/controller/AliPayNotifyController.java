package com.aid.pay.controller;

import java.io.UnsupportedEncodingException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import com.aid.common.annotation.Anonymous;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.pay.service.IAidPayOrderBussinessService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 支付宝支付回调 Controller
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/pay/notify")
public class AliPayNotifyController {

    @Resource
    private IAidPayOrderBussinessService payOrderService;

    /**
     * 支付宝回调。
     *
     * @param request HTTP请求
     * @return 处理结果(success/fail)，必须返回小写 "success" 支付宝才会停止重推
     */
    @Anonymous
    @PostMapping("/alipay")
    public String alipayNotify(HttpServletRequest request) {
        log.info("收到支付宝回调通知");

        try {
            request.setCharacterEncoding("UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 必然支持，理论上不会发生；记录但不阻断
            log.warn("设置支付宝回调请求编码失败", e);
        }

        Map<String, String> params = new HashMap<>();
        Enumeration<String> parameterNames = request.getParameterNames();
        while (parameterNames.hasMoreElements()) {
            String name = parameterNames.nextElement();
            String value = request.getParameter(name);
            params.put(name, value);
        }

        return payOrderService.handleAlipayNotify(params);
    }
}
