package com.aid.pay.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 订单列表查询请求
 *
 * @author 视觉AID
 */
@Data
public class PayOrderQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付状态(pending/paid/failed/closed/refunded)
     */
    private String payStatus;

    /**
     * 页码
     */
    private Integer pageNum = 1;

    /**
     * 每页数量
     */
    private Integer pageSize = 10;
}
