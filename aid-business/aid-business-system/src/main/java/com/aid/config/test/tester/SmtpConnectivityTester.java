package com.aid.config.test.tester;

import java.util.Map;
import java.util.Properties;

import org.springframework.stereotype.Component;

import com.aid.common.config.test.ConfigConnectivityTester;
import com.aid.common.config.test.ConfigTestRequest;
import com.aid.common.config.test.ConfigTestResult;

import cn.hutool.core.util.StrUtil;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import lombok.extern.slf4j.Slf4j;

/**
 * 邮箱（SMTP）配置连通性测试。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class SmtpConnectivityTester implements ConfigConnectivityTester {

    /**
     * 连接/读写超时（毫秒）。
     */
    private static final String TIMEOUT_MS = "5000";

    @Override
    public String testKey() {
        return "smtp";
    }

    @Override
    public ConfigTestResult test(ConfigTestRequest request) {
        Map<String, Object> payload = request.getPayload();
        String host = TesterPayloads.str(payload, "host");
        boolean ssl = TesterPayloads.bool(payload, "ssl", true);
        // 端口缺省：SSL 默认 465，明文默认 25
        int port = TesterPayloads.integer(payload, "port", ssl ? 465 : 25);
        String username = TesterPayloads.str(payload, "username");
        String password = TesterPayloads.str(payload, "password");

        // 主机必填，提前拦截
        if (StrUtil.isBlank(host)) {
            return ConfigTestResult.fail("请填写SMTP主机");
        }

        // 调试明细仅含连接信息，绝不含授权码
        String details = StrUtil.format("host={}, port={}, ssl={}, username={}", host, port, ssl, username);

        Transport transport = null;
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", String.valueOf(port));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
            props.put("mail.smtp.timeout", TIMEOUT_MS);
            props.put("mail.smtp.writetimeout", TIMEOUT_MS);
            if (ssl) {
                // 开启 SSL 加密连接
                props.put("mail.smtp.ssl.enable", "true");
            }

            Session session = Session.getInstance(props);
            transport = session.getTransport("smtp");
            // 仅建立连接并完成鉴权，不发送邮件
            transport.connect(host, port, username, password);

            ConfigTestResult result = ConfigTestResult.ok("连接成功");
            result.setDetails(details);
            return result;
        } catch (AuthenticationFailedException e) {
            // 鉴权失败：账号或授权码错误
            log.warn("SMTP连通性测试鉴权失败: host={}, port={}", host, port);
            return failWithDetails("邮箱账号或授权码错误", details + "; " + e.getClass().getSimpleName());
        } catch (MessagingException e) {
            // 连接层异常：端口不通 / 主机不可达 / 协议错误
            log.error("SMTP连通性测试连接失败: host={}, port={}, err={}", host, port, e.getMessage());
            return failWithDetails("SMTP端口不通", details + "; " + e.getClass().getSimpleName() + ": " + StrUtil.trimToEmpty(e.getMessage()));
        } catch (Exception e) {
            log.error("SMTP连通性测试未知异常: host={}, err={}", host, e.getMessage(), e);
            return failWithDetails("邮箱配置测试失败", details + "; " + e.getClass().getSimpleName());
        } finally {
            // 释放连接资源
            closeQuietly(transport);
        }
    }

    /**
     * 安静关闭 Transport，忽略关闭异常。
     *
     * @param transport 待关闭的 Transport，可为 null
     */
    private void closeQuietly(Transport transport) {
        if (transport == null) {
            return;
        }
        try {
            if (transport.isConnected()) {
                transport.close();
            }
        } catch (MessagingException e) {
            log.warn("SMTP连通性测试关闭连接异常: {}", e.getMessage());
        }
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
