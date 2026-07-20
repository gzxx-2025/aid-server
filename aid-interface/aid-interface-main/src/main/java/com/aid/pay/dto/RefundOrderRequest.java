package com.aid.pay.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 后台订单退款请求
 *
 * @author 视觉AID
 */
@Data
public class RefundOrderRequest {

    /** 订单号（必填） */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /** 退款原因（可选，缺省为"后台运营退款"） */
    private String refundReason;
}
