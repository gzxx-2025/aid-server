package com.aid.pay.service;

import java.util.List;
import java.util.Map;

import com.aid.aid.domain.AidPayOrder;
import com.aid.pay.dto.CreateOrderRequest;
import com.aid.pay.dto.PayOrderQueryRequest;
import com.aid.pay.vo.CreateOrderVO;
import com.aid.pay.vo.PayOrderVO;
import com.aid.pay.vo.RechargePackageVO;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;

/**
 * 支付订单业务Service接口
 *
 * @author 视觉AID
 */
public interface IAidPayOrderBussinessService
{
    /**
     * 查询上架中的充值套餐列表（按排序值升序）
     *
     * @return 套餐VO列表
     */
    List<RechargePackageVO> listActivePackages();

    /**
     * 创建订单
     *
     * @param request 创建订单请求
     * @param userId  用户ID
     * @param clientIp 客户端IP
     * @return 创建订单结果
     */
    CreateOrderVO createOrder(CreateOrderRequest request, Long userId, String clientIp);

    /**
     * 根据订单号查询订单
     *
     * @param orderNo 订单号
     * @return 订单信息
     */
    AidPayOrder getOrderByOrderNo(String orderNo);

    /**
     * 查询订单状态
     *
     * @param orderNo 订单号
     * @param userId  用户ID
     * @return 订单状态信息
     */
    PayOrderVO queryOrderStatus(String orderNo, Long userId);

    /**
     * 处理支付宝回调
     *
     * @param params 回调参数
     * @return 处理结果(success/fail)
     */
    String handleAlipayNotify(Map<String, String> params);

    /**
     * 处理微信支付回调。
     *
     * @param serial    证书序列号
     * @param nonce     随机串
     * @param timestamp 时间戳
     * @param signature 签名
     * @param body      请求体
     * @return 处理结果
     */
    Map<String, Object> handleWxpayNotify(String serial, String nonce, String timestamp, String signature, String body);

    /**
     * 关闭超时订单
     *
     * @param orderNo 订单号
     */
    void closeOrder(String orderNo);

    /**
     * 查询用户订单列表
     *
     * @param request 查询请求
     * @param userId  用户ID
     * @return 订单列表
     */
    AjaxResult queryOrderList(PayOrderQueryRequest request, Long userId);

    /**
     * 同步订单状态（主动查询并处理）
     * 根据订单的支付渠道自动选择支付宝或微信支付查询接口
     *
     * @param orderNo 订单号
     * @return 处理结果
     */
    AjaxResult syncOrderStatus(String orderNo);

    /**
     * 继续支付（重新获取支付二维码）
     *
     * @param orderNo 订单号
     * @param userId  用户ID
     * @return 支付二维码信息
     */
    CreateOrderVO repayOrder(String orderNo, Long userId);

    /**
     * 取消订单
     *
     * @param orderNo 订单号
     * @param userId  用户ID
     */
    void cancelOrder(String orderNo, Long userId);

    /**
     * 订单退款（后台运营操作）。
     *
     * @param orderNo      订单号
     * @param refundReason 退款原因
     * @return 处理结果
     */
    AjaxResult refundOrder(String orderNo, String refundReason);

    /**
     * 定时兜底：扫描"待支付且已超时"的订单，主动向渠道查单并入账/关单。
     * 不能只依赖回调，回调丢失时由该兜底任务补齐——已付则补充值，未付则关单。
     */
    void autoSyncPendingExpiredOrders();
}
