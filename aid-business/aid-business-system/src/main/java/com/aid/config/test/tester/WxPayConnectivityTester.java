package com.aid.config.test.tester;

import cn.hutool.core.util.StrUtil;
import com.aid.common.config.test.ConfigConnectivityTester;
import com.aid.common.config.test.ConfigTestRequest;
import com.aid.common.config.test.ConfigTestResult;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tests WeChat Pay V3 Native configuration connectivity.
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class WxPayConnectivityTester implements ConfigConnectivityTester {

    private static final String ORDER_NOT_EXIST = "ORDER_NOT_EXIST";

    private static final String PROBE_ORDER_PREFIX = "__conntest__";

    @Override
    public String testKey() {
        return "wxpay";
    }

    @Override
    public ConfigTestResult test(ConfigTestRequest request) {
        Map<String, Object> payload = request.getPayload();
        String mchId = TesterPayloads.str(payload, "mchId");
        String appId = TesterPayloads.str(payload, "appId");
        String apiV3Key = TesterPayloads.str(payload, "apiV3Key");
        String privateKey = TesterPayloads.str(payload, "privateKey");
        String serialNo = TesterPayloads.str(payload, "serialNo");
        String publicKeyId = TesterPayloads.str(payload, "publicKeyId");
        String publicKey = TesterPayloads.str(payload, "publicKey");

        if (StrUtil.hasBlank(mchId, apiV3Key, privateKey, serialNo)) {
            return ConfigTestResult.fail("请填写完整微信支付配置");
        }

        if (isPublicKeyModePartiallyConfigured(publicKeyId, publicKey)) {
            return ConfigTestResult.fail("公钥未配全");
        }

        boolean publicKeyMode = isPublicKeyMode(publicKeyId, publicKey);
        String details = "mchId=" + mchId + ", appId=" + appId + ", publicKeyMode=" + publicKeyMode;
        try {
            Config config = buildConfig(mchId, apiV3Key, privateKey, serialNo, publicKeyId, publicKey);
            NativePayService service = new NativePayService.Builder().config(config).build();

            QueryOrderByOutTradeNoRequest queryRequest = new QueryOrderByOutTradeNoRequest();
            queryRequest.setMchid(mchId);
            queryRequest.setOutTradeNo(PROBE_ORDER_PREFIX + System.currentTimeMillis());
            service.queryOrderByOutTradeNo(queryRequest);

            ConfigTestResult result = ConfigTestResult.ok("连接成功", "wxpay");
            result.setDetails(details + "; query=ok");
            return result;
        } catch (ServiceException e) {
            String errorCode = StrUtil.trimToEmpty(e.getErrorCode());
            if (ORDER_NOT_EXIST.equalsIgnoreCase(errorCode)) {
                ConfigTestResult result = ConfigTestResult.ok("连接成功", "wxpay");
                result.setDetails(details + "; errorCode=ORDER_NOT_EXIST");
                return result;
            }
            log.warn("微信支付连通性测试网关返回错误, errorCode={}, httpStatus={}", errorCode, e.getHttpStatusCode());
            return failWithDetails("商户或证书错误",
                    details + "; errorCode=" + errorCode + ", httpStatus=" + e.getHttpStatusCode());
        } catch (Exception e) {
            log.error("微信支付连通性测试异常 {}", e.getMessage(), e);
            return failWithDetails("商户或证书错误",
                    details + "; " + e.getClass().getSimpleName() + ": " + StrUtil.trimToEmpty(e.getMessage()));
        }
    }

    private Config buildConfig(String mchId, String apiV3Key, String privateKey, String serialNo,
                               String publicKeyId, String publicKey) {
        if (isPublicKeyMode(publicKeyId, publicKey)) {
            return new RSAPublicKeyConfig.Builder()
                    .merchantId(mchId)
                    .privateKey(privateKey)
                    .merchantSerialNumber(serialNo)
                    .publicKeyId(publicKeyId)
                    .publicKey(publicKey)
                    .apiV3Key(apiV3Key)
                    .build();
        }

        return new RSAAutoCertificateConfig.Builder()
                .merchantId(mchId)
                .privateKey(privateKey)
                .merchantSerialNumber(serialNo)
                .apiV3Key(apiV3Key)
                .build();
    }

    private boolean isPublicKeyMode(String publicKeyId, String publicKey) {
        return StrUtil.isNotBlank(publicKeyId) && StrUtil.isNotBlank(publicKey);
    }

    private boolean isPublicKeyModePartiallyConfigured(String publicKeyId, String publicKey) {
        boolean hasPublicKeyId = StrUtil.isNotBlank(publicKeyId);
        boolean hasPublicKey = StrUtil.isNotBlank(publicKey);
        return hasPublicKeyId ^ hasPublicKey;
    }

    private ConfigTestResult failWithDetails(String message, String details) {
        ConfigTestResult result = ConfigTestResult.fail(message);
        result.setDetails(details);
        return result;
    }
}
