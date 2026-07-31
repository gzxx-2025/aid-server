package com.aid.media.yyz.controller;

import com.aid.common.annotation.Anonymous;
import com.aid.common.aid.crypto.annotation.CryptoIgnore;
import com.aid.common.core.domain.AjaxResult;
import com.aid.media.dto.ViduCallbackContext;
import com.aid.media.service.ViduCallbackService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Enumeration;

/**
 * Vidu 媒体任务回调接收端点。
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/api/media/callback")
@RequiredArgsConstructor
public class ViduCallbackController {

    private final ViduCallbackService viduCallbackService;

    /**
     * 接收 Vidu 状态回调并固定返回成功 ACK。
     *
     * @param rawBody 回调原始报文
     * @param httpRequest HTTP 请求
     * @return 成功 ACK
     */
    @Anonymous
    @CryptoIgnore
    @PostMapping("/vidu")
    public AjaxResult onViduCallback(@RequestBody(required = false) String rawBody,
                                     HttpServletRequest httpRequest) {
        viduCallbackService.handleViduCallback(rawBody, buildContext(httpRequest));
        return AjaxResult.success();
    }

    /** 将 Servlet 请求头转换为业务层回调上下文。 */
    private ViduCallbackContext buildContext(HttpServletRequest request) {
        ViduCallbackContext context = new ViduCallbackContext();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) {
            return context;
        }
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            context.putHeader(name, request.getHeader(name));
        }
        return context;
    }
}
