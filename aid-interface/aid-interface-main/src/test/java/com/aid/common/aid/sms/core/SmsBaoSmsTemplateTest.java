package com.aid.common.aid.sms.core;

import com.aid.common.aid.sms.exception.SmsException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmsBaoSmsTemplateTest {

    @Test
    void shouldRenderVerificationCode() {
        String content = SmsBaoSmsTemplate.renderContent("【视觉AID】您的验证码是{code}", Map.of("code", "123456"));

        assertEquals("【视觉AID】您的验证码是123456", content);
    }

    @Test
    void shouldRejectTemplateWithoutCodePlaceholder() {
        assertThrows(SmsException.class,
                () -> SmsBaoSmsTemplate.renderContent("【视觉AID】欢迎使用", Map.of("code", "123456")));
    }

    @Test
    void shouldRejectBlankVerificationCode() {
        assertThrows(SmsException.class,
                () -> SmsBaoSmsTemplate.renderContent("【视觉AID】验证码{code}", Map.of("code", " ")));
    }
}
