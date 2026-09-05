package com.aid.common.aid.sms.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.aid.common.aid.sms.config.properties.SmsProperties;
import com.aid.common.aid.sms.entity.SmsResult;
import com.aid.common.aid.sms.exception.SmsException;
import com.aid.common.aid.sms.utils.SmsBaoResponseCodes;
import com.aid.common.utils.log.LogSanitizer;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 短信宝 HTTP 短信实现。
 * 内容模板在本系统中维护，例如「【视觉AID】您的验证码是{code}」。
 *
 * @author 视觉AID
 */
@Slf4j
public class SmsBaoSmsTemplate implements SmsTemplate {

    /** 短信宝安全发送接口 */
    private static final String SEND_ENDPOINT = "https://api.smsbao.com/sms";

    /** 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** 读取超时（毫秒） */
    private static final int READ_TIMEOUT_MS = 10000;

    private final SmsProperties properties;

    public SmsBaoSmsTemplate(SmsProperties properties) {
        this.properties = properties;
    }

    @Override
    public SmsResult send(String phones, String templateId, Map<String, String> param) {
        validateConfig(phones);
        // 短信宝没有远端模板 ID：非空 templateId 作为当前业务的本地内容模板；
        // 验证码等既有调用传空时继续使用系统短信宝默认模板。
        String contentTemplate = StrUtil.blankToDefault(templateId, properties.getSmsBaoContentTemplate());
        String content = renderContent(contentTemplate, param);

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("u", properties.getSmsBaoUsername().trim());
        query.put("p", properties.getSmsBaoApiKey().trim());
        if (StrUtil.isNotBlank(properties.getSmsBaoProductId())) {
            query.put("g", properties.getSmsBaoProductId().trim());
        }
        query.put("m", phones.trim());
        query.put("c", content);

        try (HttpResponse response = HttpRequest.get(SEND_ENDPOINT)
                .charset(StandardCharsets.UTF_8)
                .form(query)
                .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .execute()) {
            String body = StrUtil.trimToEmpty(response.body());
            boolean success = response.isOk() && SmsBaoResponseCodes.isSuccess(body);
            String message = success
                    ? "短信宝已受理"
                    : response.isOk() ? SmsBaoResponseCodes.describe(body) : "短信宝连接失败";
            if (!success) {
                log.error("短信宝发送失败: phone={}, status={}, code={}",
                        LogSanitizer.maskPhone(phones), response.getStatus(), SmsBaoResponseCodes.firstLine(body));
            }
            return SmsResult.builder()
                    .isSuccess(success)
                    .message(message)
                    .response(SmsBaoResponseCodes.firstLine(body))
                    .build();
        } catch (Exception e) {
            // 短信宝密钥位于查询参数中，异常信息可能携带完整 URL，日志中只记录异常类型。
            log.error("短信宝请求异常: phone={}, exception={}",
                    LogSanitizer.maskPhone(phones), e.getClass().getSimpleName());
            throw new SmsException("短信发送失败");
        }
    }

    /** 校验短信宝发送所需的最小配置。 */
    private void validateConfig(String phones) {
        if (StrUtil.isBlank(phones)) {
            log.info("短信宝发送失败: 手机号为空");
            throw new SmsException("手机号不能为空");
        }
        if (StrUtil.hasBlank(properties.getSmsBaoUsername(), properties.getSmsBaoApiKey())) {
            log.info("短信宝发送失败: 账号或密钥未配置");
            throw new SmsException("短信宝配置不全");
        }
    }

    /**
     * 将参数写入本地短信内容模板，参数名使用 {name} 形式。
     */
    static String renderContent(String template, Map<String, String> param) {
        if (StrUtil.isBlank(template)) {
            log.info("短信宝发送失败: 内容模板为空");
            throw new SmsException("短信模板不能为空");
        }
        String content = template;
        if (param != null) {
            for (Map.Entry<String, String> entry : param.entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                content = content.replace(placeholder, StrUtil.nullToEmpty(entry.getValue()));
            }
        }
        if (content.matches("(?s).*\\{[A-Za-z_][A-Za-z0-9_]*}.*")) {
            log.info("短信宝发送失败: 内容模板存在未替换占位符");
            throw new SmsException("短信模板参数缺失");
        }
        return content;
    }
}
