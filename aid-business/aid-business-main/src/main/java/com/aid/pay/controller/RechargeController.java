package com.aid.pay.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.pay.dto.CreateOrderRequest;
import com.aid.pay.dto.OrderNoRequest;
import com.aid.pay.dto.PayOrderQueryRequest;
import com.aid.pay.service.IAidPayOrderBussinessService;
import com.aid.pay.vo.CreateOrderVO;
import com.aid.pay.vo.PayOrderVO;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.common.utils.ip.IpUtils;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 充值相关接口
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/recharge")
public class RechargeController {

    @Resource
    private IAidPayOrderBussinessService payOrderService;

    /**
     * 获取套餐列表（上架中的充值套餐，按排序值升序）
     *
     * @return 套餐列表
     */
    @PostMapping("/package/list")
    public AjaxResult getPackageList() {
        return AjaxResult.success(payOrderService.listActivePackages());
    }

    /**
     * 创建订单
     *
     * @param request 创建订单请求
     * @return 订单信息（包含支付表单）
     */
    @PostMapping("/order/create")
    public AjaxResult createOrder(@Valid @RequestBody CreateOrderRequest request) {
        Long userId = SecurityUtils.getUserId();
        String clientIp = IpUtils.getIpAddr();

        CreateOrderVO result = payOrderService.createOrder(request, userId, clientIp);
        return AjaxResult.success(result);
    }

    /**
     * 查询订单状态
     *
     * @param request 订单号请求
     * @return 订单状态
     */
    @PostMapping("/order/query")
    public AjaxResult queryOrder(@Valid @RequestBody OrderNoRequest request) {
        Long userId = SecurityUtils.getUserId();
        PayOrderVO result = payOrderService.queryOrderStatus(request.getOrderNo(), userId);
        return AjaxResult.success(result);
    }

    /**
     * 查询订单列表
     *
     * @param request 查询请求
     * @return 订单列表
     */
    @PostMapping("/order/list")
    public AjaxResult queryOrderList(@RequestBody PayOrderQueryRequest request) {
        Long userId = SecurityUtils.getUserId();
        return payOrderService.queryOrderList(request, userId);
    }

    /**
     * 继续支付（重新获取支付二维码）
     *
     * @param request 订单号请求
     * @return 支付二维码信息
     */
    @PostMapping("/order/repay")
    public AjaxResult repayOrder(@Valid @RequestBody OrderNoRequest request) {
        Long userId = SecurityUtils.getUserId();
        CreateOrderVO result = payOrderService.repayOrder(request.getOrderNo(), userId);
        return AjaxResult.success(result);
    }

    /**
     * 取消订单
     *
     * @param request 订单号请求
     * @return 结果
     */
    @PostMapping("/order/cancel")
    public AjaxResult cancelOrder(@Valid @RequestBody OrderNoRequest request) {
        Long userId = SecurityUtils.getUserId();
        payOrderService.cancelOrder(request.getOrderNo(), userId);
        return AjaxResult.success("取消成功");
    }
}
