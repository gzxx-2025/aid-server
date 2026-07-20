package com.aid.common.aid.alipay.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alipay.api.*;
import com.alipay.api.domain.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import com.alipay.api.response.*;
import com.aid.common.aid.alipay.config.AlipayConfigManager;
import com.aid.common.aid.alipay.entity.AlipayRefundResult;
import com.aid.common.aid.alipay.entity.AlipayTradeResult;
import com.aid.common.aid.alipay.exception.AlipayException;
import com.aid.common.aid.alipay.properties.AlipayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付宝模板工厂
 * - 配置通过 AlipayConfigManager 管理，手动刷新
 * - 支持V3版本API
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlipayTemplateFactory {

    private final AlipayConfigManager alipayConfigManager;

    /**
     * 缓存的支付宝客户端（单例复用）。
     * {@link DefaultAlipayClient} 是线程安全的、官方建议复用的重对象（初始化会解析密钥、
     * 构建 HTTP 客户端）。每次下单/查询/退款/关单都 new 一个会浪费资源并增加 GC 压力。
     * 这里缓存复用，仅在 {@link #refresh()}（配置变更）时失效重建。
     */
    private volatile AlipayClient cachedClient;

    /**
     * 获取支付宝客户端
     */
    public AlipayClient getAlipayClient() {
        AlipayProperties properties = alipayConfigManager.getAlipayProperties();
        if (!properties.getEnabled()) {
            throw new AlipayException("支付宝服务未启用");
        }
        AlipayClient client = cachedClient;
        if (client == null) {
            synchronized (this) {
                client = cachedClient;
                if (client == null) {
                    client = createAlipayClient(properties);
                    cachedClient = client;
                    log.info("支付宝客户端已构建并缓存: appId={}", properties.getAppId());
                }
            }
        }
        return client;
    }

    /**
     * 刷新配置（配置更新后调用）
     */
    public void refresh() {
        alipayConfigManager.refresh();
        // 配置变更后失效缓存的客户端，下次按新配置重建
        synchronized (this) {
            cachedClient = null;
        }
        log.info("支付宝配置已刷新");
    }

    /**
     * 获取当前配置信息（供前端展示）
     */
    public Map<String, String> getCurrentConfig() {
        return alipayConfigManager.getCurrentConfig();
    }
    /**
     * 扫码支付（当面付）。
     *
     * @param outTradeNo  商户订单号
     * @param totalAmount 订单金额
     * @param subject     订单标题
     * @return 支付二维码链接
     */
    public String precreatePay(String outTradeNo, BigDecimal totalAmount, String subject) {
        return precreatePay(outTradeNo, totalAmount, subject, null);
    }

    /**
     * 扫码支付（当面付）。
     *
     * @param outTradeNo  商户订单号
     * @param totalAmount 订单金额
     * @param subject     订单标题
     * @param body        订单描述
     * @return 支付二维码链接
     */
    public String precreatePay(String outTradeNo, BigDecimal totalAmount, String subject, String body) {
        try {
            AlipayClient alipayClient = getAlipayClient();
            AlipayProperties properties = alipayConfigManager.getAlipayProperties();

            // 创建当面付扫码支付请求
            AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
            request.setNotifyUrl(properties.getNotifyUrl());

            // 设置请求参数
            JSONObject bizContent = new JSONObject();
            bizContent.put("out_trade_no", outTradeNo);
            bizContent.put("total_amount", totalAmount.toString());
            bizContent.put("subject", subject);
            if (body != null && !body.isEmpty()) {
                bizContent.put("body", body);
            }
            request.setBizContent(bizContent.toString());

            // 调用SDK获取二维码
            AlipayTradePrecreateResponse response = alipayClient.execute(request);
            if (response.isSuccess()) {
                log.info("支付宝扫码支付订单创建成功: outTradeNo={}, qrCode={}", outTradeNo, response.getQrCode());
                return response.getQrCode();
            } else {
                log.error("支付宝扫码支付订单创建失败: code={}, msg={}, subCode={}, subMsg={}",
                        response.getCode(), response.getMsg(), response.getSubCode(), response.getSubMsg());
                throw new AlipayException(response.getCode(), response.getSubCode(), response.getSubMsg());
            }
        } catch (AlipayApiException e) {
            log.error("支付宝扫码支付异常: {}", e.getMessage(), e);
            throw new AlipayException("支付宝支付异常: " + e.getMessage(), e);
        }
    }
    /**
     * 电脑网站支付（PC跳转支付）。
     *
     * @param outTradeNo  商户订单号
     * @param totalAmount 订单金额
     * @param subject     订单标题
     * @return 支付表单HTML
     */
    public String pagePay(String outTradeNo, BigDecimal totalAmount, String subject) {
        return pagePay(outTradeNo, totalAmount, subject, null);
    }

    /**
     * 电脑网站支付（PC跳转支付）。
     *
     * @param outTradeNo  商户订单号
     * @param totalAmount 订单金额
     * @param subject     订单标题
     * @param body        订单描述
     * @return 支付表单HTML
     */
    public String pagePay(String outTradeNo, BigDecimal totalAmount, String subject, String body) {
        try {
            AlipayClient alipayClient = getAlipayClient();
            AlipayProperties properties = alipayConfigManager.getAlipayProperties();

            // 创建API对应的request
            AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
            request.setNotifyUrl(properties.getNotifyUrl());
            request.setReturnUrl(properties.getReturnUrl());

            // 设置请求参数
            JSONObject bizContent = new JSONObject();
            bizContent.put("out_trade_no", outTradeNo);
            bizContent.put("total_amount", totalAmount.toString());
            bizContent.put("subject", subject);
            bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");
            if (body != null && !body.isEmpty()) {
                bizContent.put("body", body);
            }
            request.setBizContent(bizContent.toString());

            // 调用SDK生成表单
            AlipayTradePagePayResponse response = alipayClient.pageExecute(request);
            if (response.isSuccess()) {
                log.info("支付宝PC网站支付订单创建成功: outTradeNo={}", outTradeNo);
                return response.getBody();
            } else {
                log.error("支付宝PC网站支付订单创建失败: code={}, msg={}, subCode={}, subMsg={}",
                        response.getCode(), response.getMsg(), response.getSubCode(), response.getSubMsg());
                throw new AlipayException(response.getCode(), response.getSubCode(), response.getSubMsg());
            }
        } catch (AlipayApiException e) {
            log.error("支付宝PC网站支付异常: {}", e.getMessage(), e);
            throw new AlipayException("支付宝支付异常: " + e.getMessage(), e);
        }
    }
    /**
     * 交易查询
     *
     * @param outTradeNo 商户订单号
     * @return 交易结果
     */
    public AlipayTradeResult query(String outTradeNo) {
        try {
            AlipayClient alipayClient = getAlipayClient();

            AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
            JSONObject bizContent = new JSONObject();
            bizContent.put("out_trade_no", outTradeNo);
            request.setBizContent(bizContent.toString());

            AlipayTradeQueryResponse response = alipayClient.execute(request);
            AlipayTradeResult result = new AlipayTradeResult();

            if (response.isSuccess()) {
                result.setSuccess(true);
                result.setOutTradeNo(response.getOutTradeNo());
                result.setTradeNo(response.getTradeNo());
                result.setTradeStatus(response.getTradeStatus());
                result.setTotalAmount(new BigDecimal(response.getTotalAmount()));
                if (response.getReceiptAmount() != null) {
                    result.setReceiptAmount(new BigDecimal(response.getReceiptAmount()));
                }
                result.setBuyerLogonId(response.getBuyerLogonId());
                result.setBuyerUserId(response.getBuyerUserId());
                result.setGmtPayment(response.getSendPayDate());
                log.info("支付宝交易查询成功: outTradeNo={}, tradeStatus={}", outTradeNo, response.getTradeStatus());
            } else {
                result.setSuccess(false);
                result.setCode(response.getCode());
                result.setMsg(response.getMsg());
                result.setSubCode(response.getSubCode());
                result.setSubMsg(response.getSubMsg());
                log.error("支付宝交易查询失败: outTradeNo={}, code={}, msg={}", outTradeNo, response.getCode(), response.getMsg());
            }
            return result;
        } catch (AlipayApiException e) {
            log.error("支付宝交易查询异常: {}", e.getMessage(), e);
            return AlipayTradeResult.fail("ERROR", "支付宝查询异常: " + e.getMessage());
        }
    }
    /**
     * 退款
     *
     * @param outTradeNo  商户订单号
     * @param refundAmount 退款金额
     * @param refundReason 退款原因
     * @return 退款结果
     */
    public AlipayRefundResult refund(String outTradeNo, BigDecimal refundAmount, String refundReason) {
        return refund(outTradeNo, refundAmount, refundReason, null);
    }

    /**
     * 退款。
     *
     * @param outTradeNo   商户订单号
     * @param refundAmount 退款金额
     * @param refundReason 退款原因
     * @param outRequestNo 退款请求号（部分退款时必传）
     * @return 退款结果
     */
    public AlipayRefundResult refund(String outTradeNo, BigDecimal refundAmount, String refundReason, String outRequestNo) {
        try {
            AlipayClient alipayClient = getAlipayClient();

            AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
            JSONObject bizContent = new JSONObject();
            bizContent.put("out_trade_no", outTradeNo);
            bizContent.put("refund_amount", refundAmount.toString());
            bizContent.put("refund_reason", refundReason);
            if (outRequestNo != null && !outRequestNo.isEmpty()) {
                bizContent.put("out_request_no", outRequestNo);
            }
            request.setBizContent(bizContent.toString());

            AlipayTradeRefundResponse response = alipayClient.execute(request);
            AlipayRefundResult result = new AlipayRefundResult();

            if (response.isSuccess()) {
                result.setSuccess(true);
                result.setOutTradeNo(response.getOutTradeNo());
                result.setTradeNo(response.getTradeNo());
                result.setRefundAmount(new BigDecimal(response.getRefundFee()));
                result.setRefundStatus("Y");
                log.info("支付宝退款成功: outTradeNo={}, refundAmount={}", outTradeNo, refundAmount);
            } else {
                result.setSuccess(false);
                result.setCode(response.getCode());
                result.setMsg(response.getMsg());
                result.setSubCode(response.getSubCode());
                result.setSubMsg(response.getSubMsg());
                log.error("支付宝退款失败: outTradeNo={}, code={}, msg={}", outTradeNo, response.getCode(), response.getMsg());
            }
            return result;
        } catch (AlipayApiException e) {
            log.error("支付宝退款异常: {}", e.getMessage(), e);
            return AlipayRefundResult.fail("ERROR", "支付宝退款异常: " + e.getMessage());
        }
    }

    /**
     * 退款查询：用于退款前检测渠道侧是否已存在退款，避免重复退款。
     *
     * @param outTradeNo   商户订单号
     * @param outRequestNo 退款请求号（为空时取 outTradeNo）
     * @return 退款查询结果；{@code refundAmount > 0} 表示渠道侧已存在退款
     */
    public AlipayRefundResult refundQuery(String outTradeNo, String outRequestNo) {
        try {
            AlipayClient alipayClient = getAlipayClient();

            AlipayTradeFastpayRefundQueryRequest request = new AlipayTradeFastpayRefundQueryRequest();
            JSONObject bizContent = new JSONObject();
            bizContent.put("out_trade_no", outTradeNo);
            bizContent.put("out_request_no", (outRequestNo != null && !outRequestNo.isEmpty()) ? outRequestNo : outTradeNo);
            request.setBizContent(bizContent.toString());

            AlipayTradeFastpayRefundQueryResponse response = alipayClient.execute(request);
            AlipayRefundResult result = new AlipayRefundResult();

            if (response.isSuccess()) {
                result.setSuccess(true);
                result.setOutTradeNo(response.getOutTradeNo());
                result.setTradeNo(response.getTradeNo());
                result.setOutRequestNo(response.getOutRequestNo());
                // refund_amount 有值表示渠道侧已存在退款记录
                String refundAmount = response.getRefundAmount();
                if (refundAmount != null && !refundAmount.isEmpty()) {
                    result.setRefundAmount(new BigDecimal(refundAmount));
                }
                log.info("支付宝退款查询成功: outTradeNo={}, refundAmount={}", outTradeNo, refundAmount);
            } else {
                result.setSuccess(false);
                result.setCode(response.getCode());
                result.setMsg(response.getMsg());
                result.setSubCode(response.getSubCode());
                result.setSubMsg(response.getSubMsg());
                log.warn("支付宝退款查询失败: outTradeNo={}, code={}, msg={}", outTradeNo, response.getCode(), response.getMsg());
            }
            return result;
        } catch (AlipayApiException e) {
            log.error("支付宝退款查询异常: {}", e.getMessage(), e);
            return AlipayRefundResult.fail("ERROR", "支付宝退款查询异常: " + e.getMessage());
        }
    }
    /**
     * 关闭订单
     *
     * @param outTradeNo 商户订单号
     * @return 交易结果
     */
    public AlipayTradeResult close(String outTradeNo) {
        try {
            AlipayClient alipayClient = getAlipayClient();

            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            JSONObject bizContent = new JSONObject();
            bizContent.put("out_trade_no", outTradeNo);
            request.setBizContent(bizContent.toString());

            AlipayTradeCloseResponse response = alipayClient.execute(request);
            AlipayTradeResult result = new AlipayTradeResult();

            if (response.isSuccess()) {
                result.setSuccess(true);
                result.setOutTradeNo(response.getOutTradeNo());
                result.setTradeNo(response.getTradeNo());
                log.info("支付宝订单关闭成功: outTradeNo={}", outTradeNo);
            } else {
                result.setSuccess(false);
                result.setCode(response.getCode());
                result.setMsg(response.getMsg());
                result.setSubCode(response.getSubCode());
                result.setSubMsg(response.getSubMsg());
                log.error("支付宝订单关闭失败: outTradeNo={}, code={}, msg={}", outTradeNo, response.getCode(), response.getMsg());
            }
            return result;
        } catch (AlipayApiException e) {
            log.error("支付宝订单关闭异常: {}", e.getMessage(), e);
            return AlipayTradeResult.fail("ERROR", "支付宝关闭订单异常: " + e.getMessage());
        }
    }
    /**
     * 验证异步通知签名
     *
     * @param params 支付宝回调参数
     * @return 验签是否成功
     */
    public boolean verifyNotify(Map<String, String> params) {
        try {
            AlipayProperties properties = alipayConfigManager.getAlipayProperties();
            return AlipaySignature.rsaCheckV1(
                    params,
                    properties.getAlipayPublicKey(),
                    properties.getCharset(),
                    properties.getSignType()
            );
        } catch (AlipayApiException e) {
            log.error("支付宝异步通知验签失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 校验异步通知来源是否属于本应用/本商户。
     *
     * @param params 支付宝回调参数
     * @return 来源是否合法
     */
    public boolean verifyNotifySource(Map<String, String> params) {
        AlipayProperties properties = alipayConfigManager.getAlipayProperties();

        String expectAppId = properties.getAppId();
        String notifyAppId = params.get("app_id");
        if (expectAppId == null || expectAppId.isBlank()) {
            log.error("支付宝回调来源校验失败：未配置 appId");
            return false;
        }
        if (!expectAppId.equals(notifyAppId)) {
            log.error("支付宝回调来源校验失败：app_id 不匹配, notifyAppId={}", notifyAppId);
            return false;
        }

        String expectPid = properties.getPid();
        if (expectPid != null && !expectPid.isBlank()) {
            String notifySellerId = params.get("seller_id");
            if (!expectPid.equals(notifySellerId)) {
                log.error("支付宝回调来源校验失败：seller_id 不匹配, notifySellerId={}", notifySellerId);
                return false;
            }
        }

        return true;
    }

    /**
     * 解析异步通知参数
     *
     * @param params 支付宝回调参数
     * @return 交易结果
     */
    public AlipayTradeResult parseNotify(Map<String, String> params) {
        // 验签
        if (!verifyNotify(params)) {
            log.error("支付宝异步通知验签失败");
            return AlipayTradeResult.fail("VERIFY_FAILED", "验签失败");
        }

        AlipayTradeResult result = new AlipayTradeResult();
        result.setSuccess(true);
        result.setOutTradeNo(params.get("out_trade_no"));
        result.setTradeNo(params.get("trade_no"));
        result.setTradeStatus(params.get("trade_status"));
        String totalAmount = params.get("total_amount");
        if (totalAmount != null) {
            result.setTotalAmount(new BigDecimal(totalAmount));
        }
        String receiptAmount = params.get("receipt_amount");
        if (receiptAmount != null) {
            result.setReceiptAmount(new BigDecimal(receiptAmount));
        }
        result.setBuyerLogonId(params.get("buyer_logon_id"));
        result.setBuyerUserId(params.get("buyer_id"));
        String gmtPayment = params.get("gmt_payment");
        if (gmtPayment != null) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                result.setGmtPayment(sdf.parse(gmtPayment));
            } catch (Exception e) {
                log.warn("解析支付时间失败: {}", gmtPayment);
            }
        }

        log.info("支付宝异步通知解析成功: outTradeNo={}, tradeStatus={}", result.getOutTradeNo(), result.getTradeStatus());
        return result;
    }
    /**
     * 创建支付宝客户端
     */
    private AlipayClient createAlipayClient(AlipayProperties properties) {
        String serverUrl = properties.getSandbox()
                ? "https://openapi-sandbox.dl.alipaydev.com/gateway.do"
                : "https://openapi.alipay.com/gateway.do";

        return new DefaultAlipayClient(
                serverUrl,
                properties.getAppId(),
                properties.getPrivateKey(),
                properties.getFormat(),
                properties.getCharset(),
                properties.getAlipayPublicKey(),
                properties.getSignType()
        );
    }
}
