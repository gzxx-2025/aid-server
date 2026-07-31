package com.aid.pay.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建订单请求参数
 *
 * @author 视觉AID
 */
@Data
public class CreateOrderRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 套餐ID
     */
    @NotNull(message = "套餐ID不能为空")
    private Long packageId;

    /**
     * 支付方式(alipay/wxpay)
     */
    @NotNull(message = "支付方式不能为空")
    private String payType;
}
