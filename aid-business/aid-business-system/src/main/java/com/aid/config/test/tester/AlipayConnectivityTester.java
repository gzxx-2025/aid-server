package com.aid.config.test.tester;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipaySystemOauthTokenRequest;
import com.alipay.api.response.AlipaySystemOauthTokenResponse;
import com.aid.common.config.test.ConfigConnectivityTester;
import com.aid.common.config.test.ConfigTestRequest;
import com.aid.common.config.test.ConfigTestResult;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 支付宝配置连通性测试。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class AlipayConnectivityTester implements ConfigConnectivityTester {

    /**
     * 默认生产网关地址。
     */
    private static final String DEFAULT_GATEWAY = "https://openapi.alipay.com/gateway.do";

    /**
     * 沙箱网关地址。
     */
    private static final String SANDBOX_GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";

    /**
     * 连通性探活使用的无效授权码（不可能命中真实授权）。
     */
    private static final String PROBE_INVALID_CODE = "conntest_invalid_code";

    @Override
    public String testKey() {
        return "alipay";
    }

    @Override
    public ConfigTestResult test(ConfigTestRequest request) {
        Map<String, Object> payload = request.getPayload();
        String appId = TesterPayloads.str(payload, "appId");
        String privateKey = TesterPayloads.str(payload, "privateKey");
        String alipayPublicKey = TesterPayloads.str(payload, "alipayPublicKey");
        boolean sandbox = TesterPayloads.bool(payload, "sandbox", false);
        // 网关严格按「是否沙箱」动态判定，与生产 AlipayTemplateFactory.createAlipayClient 完全一致：
        // sandbox=true → 沙箱网关；否则 → 正式网关。不再读不存在的 gatewayUrl 字段。
        String gatewayUrl = sandbox ? SANDBOX_GATEWAY : DEFAULT_GATEWAY;
        String envTag = sandbox ? "沙箱" : "正式";

        // 必填项校验，提前拦截，避免无意义的网络请求
        if (StrUtil.hasBlank(appId, privateKey, alipayPublicKey)) {
            return ConfigTestResult.fail("请填写完整支付宝配置");
        }

        try {
            // 用临时配置构造客户端（公钥模式，签名类型 RSA2）
            AlipayClient client = new DefaultAlipayClient(
                    gatewayUrl, appId, privateKey, "json", "UTF-8", alipayPublicKey, "RSA2");

            // 只读接口 + 无效 code：用于探活，不会产生任何副作用
            AlipaySystemOauthTokenRequest oauthRequest = new AlipaySystemOauthTokenRequest();
            oauthRequest.setGrantType("authorization_code");
            oauthRequest.setCode(PROBE_INVALID_CODE);

            AlipaySystemOauthTokenResponse response = client.execute(oauthRequest);
            String subCode = StrUtil.trimToEmpty(response.getSubCode());
            String code = StrUtil.trimToEmpty(response.getCode());

            // 调试明细：含所用环境/网关与网关返回码，绝不含密钥
            String details = StrUtil.format("env={}, gateway={}, isSuccess={}, code={}, subCode={}, subMsg={}",
                    envTag, gatewayUrl, response.isSuccess(), code, subCode, StrUtil.trimToEmpty(response.getSubMsg()));

            // 凭证错误特征：app-id 不存在 / 验签失败 / 密钥不匹配
            if (isCredentialError(subCode)) {
                log.warn("支付宝连通性测试: 凭证错误, env={}, code={}, subCode={}", envTag, code, subCode);
                return failWithDetails("AppID或私钥不正确", details);
            }

            // 走到这里说明网关已接受签名（仅授权码无效），即连通成功
            ConfigTestResult result = ConfigTestResult.ok("连接成功(" + envTag + ")", "alipay");
            result.setDetails(details);
            return result;
        } catch (AlipayApiException e) {
            // 签名失败 / 网络异常等，归类为配置不正确（异常 message 由 SDK 产生，不含私钥明文）
            log.error("支付宝连通性测试异常: env={}, {}", envTag, e.getErrMsg(), e);
            return failWithDetails("AppID或私钥不正确",
                    "env=" + envTag + ", AlipayApiException: " + StrUtil.trimToEmpty(e.getErrMsg()));
        } catch (Exception e) {
            log.error("支付宝连通性测试未知异常: {}", e.getMessage(), e);
            return failWithDetails("支付宝配置测试失败", e.getClass().getSimpleName() + ": " + StrUtil.trimToEmpty(e.getMessage()));
        }
    }

    /**
     * 判断错误码是否属于凭证类错误（AppID/私钥/验签问题）。
     *
     * @param subCode 支付宝返回的子错误码
     * @return true 表示凭证错误
     */
    private boolean isCredentialError(String subCode) {
        if (StrUtil.isBlank(subCode)) {
            return false;
        }
        String lower = subCode.toLowerCase();
        // app-id 无效、签名错误、密钥相关均视为凭证错误
        return lower.contains("app-id")
                || lower.contains("app_id")
                || lower.contains("invalid-app")
                || lower.contains("sign")
                || lower.contains("key");
    }

    /**
     * 构造带调试明细的失败结果。
     *
     * @param message 友好文案
     * @param details 调试明细（无密钥）
     * @return 失败结果
     */
    private ConfigTestResult failWithDetails(String message, String details) {
        ConfigTestResult result = ConfigTestResult.fail(message);
        result.setDetails(details);
        return result;
    }
}
