package com.aid.config.test.tester;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.aliyun.dysmsapi20170525.models.QuerySmsSignListRequest;
import com.aliyun.dysmsapi20170525.models.QuerySmsSignListResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aid.common.config.test.ConfigConnectivityTester;
import com.aid.common.config.test.ConfigTestRequest;
import com.aid.common.config.test.ConfigTestResult;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.sms.v20190711.SmsClient;
import com.tencentcloudapi.sms.v20190711.models.DescribeSmsSignListRequest;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 短信配置连通性测试。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class SmsConnectivityTester implements ConfigConnectivityTester {

    @Override
    public String testKey() {
        return "sms";
    }

    @Override
    public ConfigTestResult test(ConfigTestRequest request) {
        Map<String, Object> payload = request.getPayload();
        String endpoint = TesterPayloads.str(payload, "endpoint");
        String provider = resolveProvider(TesterPayloads.str(payload, "provider"), endpoint);
        String accessKeyId = TesterPayloads.str(payload, "accessKeyId");
        String accessKeySecret = TesterPayloads.str(payload, "accessKeySecret");

        // 凭证必填
        if (StrUtil.hasBlank(accessKeyId, accessKeySecret)) {
            return ConfigTestResult.fail("请填写短信密钥");
        }

        try {
            if ("tencent".equals(provider)) {
                return testTencent(payload, accessKeyId, accessKeySecret, endpoint);
            }
            return testAliyun(accessKeyId, accessKeySecret, endpoint);
        } catch (Exception e) {
            log.error("短信连通性测试未知异常: provider={}, err={}", provider, e.getMessage(), e);
            return failWithDetails("短信配置测试失败",
                    "provider=" + provider + "; " + e.getClass().getSimpleName() + ": " + StrUtil.trimToEmpty(e.getMessage()));
        }
    }

    /**
     * 阿里云短信探活：QuerySmsSignList（只读）。
     *
     * @param accessKeyId     凭证 ID
     * @param accessKeySecret 凭证密钥
     * @param endpoint        接入点（缺省 dysmsapi.aliyuncs.com）
     * @return 测试结果
     */
    private ConfigTestResult testAliyun(String accessKeyId, String accessKeySecret, String endpoint) {
        String realEndpoint = StrUtil.blankToDefault(endpoint, "dysmsapi.aliyuncs.com");
        String details = "provider=aliyun, endpoint=" + realEndpoint;
        try {
            Config config = new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret)
                    .setEndpoint(realEndpoint);
            com.aliyun.dysmsapi20170525.Client client = new com.aliyun.dysmsapi20170525.Client(config);

            // 只读查询签名列表，验证凭证有效性，不发送任何短信
            QuerySmsSignListResponse response = client.querySmsSignList(new QuerySmsSignListRequest());
            String code = response.getBody() == null ? "" : StrUtil.trimToEmpty(response.getBody().getCode());
            if ("OK".equalsIgnoreCase(code)) {
                ConfigTestResult result = ConfigTestResult.ok("连接成功", "aliyun");
                result.setDetails(details + "; code=OK");
                return result;
            }
            // 非 OK：按错误码判断是否凭证问题
            if (isCredentialError(code)) {
                log.warn("阿里云短信连通性测试: 凭证错误, code={}", code);
                return failWithDetails("短信密钥或签名无效", details + "; code=" + code);
            }
            // 其它错误码说明已通过鉴权
            ConfigTestResult result = ConfigTestResult.ok("连接成功", "aliyun");
            result.setDetails(details + "; code=" + code);
            return result;
        } catch (Exception e) {
            // 阿里云 SDK 抛 TeaException，message 含错误码；据此判断
            String msg = StrUtil.trimToEmpty(e.getMessage());
            if (isCredentialError(msg)) {
                log.warn("阿里云短信连通性测试: 凭证错误, err={}", msg);
                return failWithDetails("短信密钥或签名无效", details + "; " + e.getClass().getSimpleName());
            }
            log.error("阿里云短信连通性测试连接失败: err={}", msg);
            return failWithDetails("短信服务连接失败", details + "; " + e.getClass().getSimpleName());
        }
    }

    /**
     * 腾讯云短信探活：DescribeSmsSignList（只读）。
     *
     * @param payload         临时配置（取 region）
     * @param accessKeyId     SecretId
     * @param accessKeySecret SecretKey
     * @param endpoint        接入点（缺省 sms.tencentcloudapi.com）
     * @return 测试结果
     */
    private ConfigTestResult testTencent(Map<String, Object> payload, String accessKeyId, String accessKeySecret, String endpoint) {
        String region = TesterPayloads.str(payload, "region", "ap-guangzhou");
        String realEndpoint = StrUtil.blankToDefault(endpoint, "sms.tencentcloudapi.com");
        String details = StrUtil.format("provider=tencent, endpoint={}, region={}", realEndpoint, region);
        try {
            Credential credential = new Credential(accessKeyId, accessKeySecret);
            HttpProfile httpProfile = new HttpProfile();
            httpProfile.setEndpoint(realEndpoint);
            httpProfile.setConnTimeout(5);
            ClientProfile clientProfile = new ClientProfile();
            clientProfile.setHttpProfile(httpProfile);
            SmsClient client = new SmsClient(credential, region, clientProfile);

            // 只读查询签名列表（传探活用的占位签名 ID），验证凭证有效性，不发送任何短信
            DescribeSmsSignListRequest req = new DescribeSmsSignListRequest();
            req.setSignIdSet(new Long[]{1L});
            req.setInternational(0L);
            client.DescribeSmsSignList(req);

            // 调用成功说明凭证有效且网关连通
            ConfigTestResult result = ConfigTestResult.ok("连接成功", "tencent");
            result.setDetails(details + "; describe=ok");
            return result;
        } catch (TencentCloudSDKException e) {
            String errorCode = StrUtil.trimToEmpty(e.getErrorCode());
            // 鉴权类错误码归为凭证错误；其它错误码（参数等）说明网关已通过鉴权
            if (isCredentialError(errorCode)) {
                log.warn("腾讯云短信连通性测试: 凭证错误, errorCode={}", errorCode);
                return failWithDetails("短信密钥或签名无效", details + "; errorCode=" + errorCode);
            }
            ConfigTestResult result = ConfigTestResult.ok("连接成功", "tencent");
            result.setDetails(details + "; errorCode=" + errorCode);
            return result;
        } catch (Exception e) {
            log.error("腾讯云短信连通性测试连接失败: err={}", e.getMessage());
            return failWithDetails("短信服务连接失败", details + "; " + e.getClass().getSimpleName());
        }
    }

    /**
     * 解析厂商：优先取显式 provider，否则按 endpoint 推断，默认阿里云。
     *
     * @param provider 显式厂商标识
     * @param endpoint 接入点
     * @return tencent 或 aliyun
     */
    private String resolveProvider(String provider, String endpoint) {
        String value = provider.toLowerCase();
        if (value.contains("tencent") || value.contains("qcloud")) {
            return "tencent";
        }
        if (value.contains("aliyun") || value.contains("ali")) {
            return "aliyun";
        }
        // 显式值缺失时按接入点推断
        if (StrUtil.isNotBlank(endpoint) && endpoint.toLowerCase().contains("tencent")) {
            return "tencent";
        }
        return "aliyun";
    }

    /**
     * 判断错误码/错误信息是否属于鉴权类（密钥/签名/权限问题）。
     *
     * @param codeOrMsg 错误码或错误信息
     * @return true 表示鉴权类错误
     */
    private boolean isCredentialError(String codeOrMsg) {
        if (StrUtil.isBlank(codeOrMsg)) {
            return false;
        }
        String lower = codeOrMsg.toLowerCase();
        return lower.contains("accesskey")
                || lower.contains("access_key")
                || lower.contains("signature")
                || lower.contains("authfailure")
                || lower.contains("unauthorized")
                || lower.contains("forbidden")
                || lower.contains("invalidaccesskeyid")
                || lower.contains("secretid");
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
