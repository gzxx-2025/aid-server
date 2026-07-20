package com.aid.pay.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 订单号请求DTO
 *
 * @author 视觉AID
 */
@Data
public class OrderNoRequest {

    /** 订单号 */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;
}
