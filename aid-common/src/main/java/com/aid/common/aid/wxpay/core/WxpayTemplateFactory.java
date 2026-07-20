package com.aid.common.aid.wxpay.core;

import com.aid.common.aid.wxpay.config.WxpayConfigManager;
import com.aid.common.aid.wxpay.entity.WxpayRefundResult;
import com.aid.common.aid.wxpay.entity.WxpayTradeResult;
import com.aid.common.aid.wxpay.exception.WxpayException;
import com.aid.common.aid.wxpay.properties.WxpayProperties;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.Amount;
import com.wechat.pay.java.service.payments.nativepay.model.CloseOrderRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayResponse;
import com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest;
import java.util.Date;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 微信支付模板工厂
 * - 配置通过 WxpayConfigManager 管理，手动刷新
 * - 使用微信支付V3版本API
 * - 支持Native支付（扫码支付）
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxpayTemplateFactory {

    private final WxpayConfigManager wxpayConfigManager;

    /**
     * 获取微信支付配置
     */
    public WxpayProperties getWxpayProperties() {
        return wxpayConfigManager.getWxpayProperties();
    }

    /**
     * 刷新配置（配置更新后调用）
     */
    public void refresh() {
        wxpayConfigManager.refresh();
        log.info("微信支付配置已刷新");
    }

    /**
     * 获取当前配置信息（供前端展示）
     */
    public Map<String, String> getCurrentConfig() {
        return wxpayConfigManager.getCurrentConfig();
    }
    /**
     * Native扫码支付。
     *
     * @param outTradeNo  商户订单号
     * @param totalAmount 订单金额（元）
     * @param description 商品描述
     * @return 支付二维码链接
     */
    public String nativePay(String outTradeNo, BigDecimal totalAmount, String description) {

        try {
            WxpayProperties properties = getWxpayProperties();
            if (!properties.getEnabled()) {
                throw new WxpayException("微信支付服务未启用");
            }

            Config config = createConfig(properties);
            NativePayService service = new NativePayService.Builder().config(config).build();

            // 构建请求
            PrepayRequest request = new PrepayRequest();
            request.setAppid(properties.getAppId());
            request.setMchid(properties.getMchId());
            request.setDescription(description);
            request.setOutTradeNo(outTradeNo);
            request.setNotifyUrl(properties.getNotifyUrl());

            // 金额（单位：分）
            Amount amount = new Amount();
            amount.setTotal(totalAmount.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP).intValue());
            amount.setCurrency("CNY");
            request.setAmount(amount);

            // 调用下单接口
            PrepayResponse response = service.prepay(request);
            log.info("微信Native支付订单创建成功: outTradeNo={}, codeUrl={}", outTradeNo, response.getCodeUrl());
            return response.getCodeUrl();

        } catch (WxpayException e) {
            throw e;
        } catch (Exception e) {
            log.error("微信Native支付异常: {}", e.getMessage(), e);
            throw new WxpayException("微信支付异常: " + e.getMessage(), e);
        }
    }
    /**
     * 根据商户订单号查询交易
     *
     * @param outTradeNo 商户订单号
     * @return 交易结果
     */
    public WxpayTradeResult query(String outTradeNo) {
        try {
            WxpayProperties properties = getWxpayProperties();
            if (!properties.getEnabled()) {
                throw new WxpayException("微信支付服务未启用");
            }

            Config config = createConfig(properties);
            NativePayService service = new NativePayService.Builder().config(config).build();

            QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
            request.setMchid(properties.getMchId());
            request.setOutTradeNo(outTradeNo);

            Transaction transaction = service.queryOrderByOutTradeNo(request);

            WxpayTradeResult result = new WxpayTradeResult();
            result.setSuccess(true);
            result.setOutTradeNo(transaction.getOutTradeNo());
            result.setTransactionId(transaction.getTransactionId());
            result.setTradeState(transaction.getTradeState().name());
            result.setTradeStateDesc(getTradeStateDesc(transaction.getTradeState().name()));

            if (transaction.getAmount() != null) {
                result.setTotalAmount(transaction.getAmount().getTotal());
            }
            if (transaction.getPayer() != null) {
                result.setOpenid(transaction.getPayer().getOpenid());
            }
            if (transaction.getSuccessTime() != null) {
                result.setSuccessTime(parseSuccessTime(transaction.getSuccessTime()));
            }

            log.info("微信交易查询成功: outTradeNo={}, tradeState={}", outTradeNo, transaction.getTradeState());
            return result;

        } catch (WxpayException e) {
            throw e;
        } catch (ServiceException e) {
            // 微信查单返回非 2xx（如订单不存在 ORDERNOTEXIST）会抛 ServiceException，
            // 这里保留微信真实业务错误码，供上层精准判断（关单 / 重试等）；
            // 若被吞成统一 "ERROR"，doSyncWxpayOrder 中的 ORDERNOTEXIST 关单分支将无法触发，
            // 超时且渠道侧不存在的订单会长期滞留 PENDING。
            log.error("微信交易查询失败: outTradeNo={}, httpStatus={}, errorCode={}, errorMsg={}",
                    outTradeNo, e.getHttpStatusCode(), e.getErrorCode(), e.getErrorMessage());
            return WxpayTradeResult.fail(e.getErrorCode(), e.getErrorMessage());
        } catch (Exception e) {
            log.error("微信交易查询异常: {}", e.getMessage(), e);
            return WxpayTradeResult.fail("ERROR", "微信查询异常: " + e.getMessage());
        }
    }
    /**
     * 退款。
     *
     * @param outTradeNo   商户订单号
     * @param totalAmount   订单总金额（元）
     * @param refundAmount 退款金额（元）
     * @param refundReason 退款原因
     * @return 退款结果
     */
    public WxpayRefundResult refund(String outTradeNo, BigDecimal totalAmount, BigDecimal refundAmount, String refundReason) {
        return refund(outTradeNo, totalAmount, refundAmount, refundReason, null);
    }

    /**
     * 退款。
     *
     * @param outTradeNo   商户订单号
     * @param totalAmount   订单总金额（元）
     * @param refundAmount 退款金额（元）
     * @param refundReason 退款原因
     * @param outRefundNo  退款单号（部分退款时必传）
     * @return 退款结果
     */
    public WxpayRefundResult refund(String outTradeNo, BigDecimal totalAmount, BigDecimal refundAmount,
                                     String refundReason, String outRefundNo) {
        try {
            WxpayProperties properties = getWxpayProperties();
            if (!properties.getEnabled()) {
                throw new WxpayException("微信支付服务未启用");
            }

            Config config = createConfig(properties);
            RefundService service = new RefundService.Builder().config(config).build();

            CreateRequest request = new CreateRequest();
            request.setOutTradeNo(outTradeNo);
            request.setOutRefundNo(outRefundNo != null ? outRefundNo : outTradeNo + "_R");
            request.setReason(refundReason);
            // 退款不复用支付回调地址：/pay/notify/wxpay 按"支付成功"结构（Transaction）解析，
            // 退款通知（REFUND.SUCCESS）结构不同会解析失败。这里不设置 notifyUrl，
            // 退款结果以同步返回 + 主动查单为准。

            // 退款金额
            AmountReq amount = new AmountReq();
            amount.setRefund(refundAmount.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP).longValue());
            amount.setTotal(totalAmount.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP).longValue());
            amount.setCurrency("CNY");
            request.setAmount(amount);

            Refund refund = service.create(request);

            String status = refund.getStatus() != null ? refund.getStatus().name() : null;

            WxpayRefundResult result = new WxpayRefundResult();
            result.setOutTradeNo(refund.getOutTradeNo());
            result.setRefundId(refund.getRefundId());
            result.setOutRefundNo(refund.getOutRefundNo());
            result.setRefundStatus(status);

            // 仅 SUCCESS / PROCESSING 视为退款受理成功；ABNORMAL（退款异常，资金未退出，需人工在商户平台处理）
            // 或 CLOSED（退款关闭）若误判为成功，会扣回用户积分并将订单置为已退款，
            // 造成"钱没退、积分却被扣"的资损。这里按真实状态判定成败。
            boolean accepted = "SUCCESS".equals(status) || "PROCESSING".equals(status);
            result.setSuccess(accepted);
            if (accepted) {
                log.info("微信退款受理成功: outTradeNo={}, refundAmount={}, status={}", outTradeNo, refundAmount, status);
            } else {
                result.setCode(status);
                result.setMsg("微信退款状态异常: " + status);
                log.error("微信退款状态异常，拒绝按成功处理，需人工核对: outTradeNo={}, status={}", outTradeNo, status);
            }
            return result;

        } catch (Exception e) {
            log.error("微信退款异常: {}", e.getMessage(), e);
            return WxpayRefundResult.fail("ERROR", "微信退款异常: " + e.getMessage());
        }
    }
    /**
     * 关闭订单
     *
     * @param outTradeNo 商户订单号
     * @return 是否成功
     */
    public boolean close(String outTradeNo) {
        try {
            WxpayProperties properties = getWxpayProperties();
            if (!properties.getEnabled()) {
                throw new WxpayException("微信支付服务未启用");
            }

            Config config = createConfig(properties);
            NativePayService service = new NativePayService.Builder().config(config).build();

            CloseOrderRequest request = new CloseOrderRequest();
            request.setMchid(properties.getMchId());
            request.setOutTradeNo(outTradeNo);

            service.closeOrder(request);
            log.info("微信订单关闭成功: outTradeNo={}", outTradeNo);
            return true;

        } catch (Exception e) {
            log.error("微信订单关闭异常: {}", e.getMessage(), e);
            return false;
        }
    }
    /**
     * 解析异步通知。
     *
     * @param serial    证书序列号
     * @param nonce     随机串
     * @param timestamp 时间戳
     * @param signature 签名
     * @param body      请求体
     * @return 交易结果
     */
    public WxpayTradeResult parseNotify(String serial, String nonce, String timestamp, String signature, String body) {
        try {
            WxpayProperties properties = getWxpayProperties();
            if (!properties.getEnabled()) {
                throw new WxpayException("微信支付服务未启用");
            }

            Config config = createConfig(properties);
            if (!(config instanceof NotificationConfig notificationConfig)) {
                throw new WxpayException("验签配置错误");
            }
            NotificationParser parser = new NotificationParser(notificationConfig);

            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(serial)
                    .nonce(nonce)
                    .signature(signature)
                    .timestamp(timestamp)
                    .body(body)
                    .build();

            Transaction transaction = parser.parse(requestParam, Transaction.class);

            WxpayTradeResult result = new WxpayTradeResult();
            result.setSuccess(true);
            result.setOutTradeNo(transaction.getOutTradeNo());
            result.setTransactionId(transaction.getTransactionId());
            result.setTradeState(transaction.getTradeState().name());
            result.setTradeStateDesc(getTradeStateDesc(transaction.getTradeState().name()));

            if (transaction.getAmount() != null) {
                result.setTotalAmount(transaction.getAmount().getTotal());
            }
            if (transaction.getPayer() != null) {
                result.setOpenid(transaction.getPayer().getOpenid());
            }
            if (transaction.getSuccessTime() != null) {
                result.setSuccessTime(parseSuccessTime(transaction.getSuccessTime()));
            }

            log.info("微信异步通知解析成功: outTradeNo={}, tradeState={}", result.getOutTradeNo(), result.getTradeState());
            return result;

        } catch (Exception e) {
            log.error("微信异步通知解析异常: {}", e.getMessage(), e);
            return WxpayTradeResult.fail("PARSE_ERROR", "解析失败: " + e.getMessage());
        }
    }
    /**
     * 创建配置
     * 复用 WxpayConfigManager 缓存的单例 Config，避免每次 new RSAAutoCertificateConfig
     * 起后台证书轮换线程造成线程泄漏，并触发微信证书下载频率限制。
     */
    private Config createConfig(WxpayProperties properties) {
        return wxpayConfigManager.getConfig();
    }

    /**
     * 获取交易状态描述
     */
    private String getTradeStateDesc(String tradeState) {
        return switch (tradeState) {
            case "SUCCESS" -> "支付成功";
            case "REFUND" -> "转入退款";
            case "NOTPAY" -> "未支付";
            case "CLOSED" -> "已关闭";
            case "REVOKED" -> "已撤销";
            case "USERPAYING" -> "用户支付中";
            case "PAYERROR" -> "支付失败";
            default -> tradeState;
        };
    }

    /**
     * 解析支付成功时间（RFC3339格式）
     */
    private Date parseSuccessTime(String successTime) {
        if (successTime == null || successTime.isBlank()) {
            return null;
        }
        try {
            // 微信返回的时间格式：2024-01-01T12:00:00+08:00 或 2024-01-01T12:00:00.000+08:00
            java.time.OffsetDateTime offsetDateTime = java.time.OffsetDateTime.parse(successTime);
            return Date.from(offsetDateTime.toInstant());
        } catch (Exception e) {
            log.warn("解析支付成功时间失败: {}", successTime, e);
            return null;
        }
    }
}
