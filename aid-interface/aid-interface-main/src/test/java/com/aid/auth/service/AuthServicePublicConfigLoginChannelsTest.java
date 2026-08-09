package com.aid.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aid.auth.domain.vo.PublicConfigVO;
import com.aid.common.aid.mail.config.MailConfigManager;
import com.aid.common.aid.sms.config.SmsConfigManager;
import com.aid.common.aid.wxlogin.config.WxLoginConfigManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link AuthService} 公开登录渠道开关测试。
 *
 * @author 视觉AID
 */
class AuthServicePublicConfigLoginChannelsTest
{
    @Test
    void shouldExposeEveryLoginChannelCombinationIndependently()
    {
        for (boolean smsEnabled : new boolean[]{false, true})
        {
            for (boolean emailEnabled : new boolean[]{false, true})
            {
                for (boolean wechatEnabled : new boolean[]{false, true})
                {
                    PublicConfigVO.LoginChannels result = buildChannels(
                            smsEnabled, emailEnabled, wechatEnabled);

                    assertEquals(smsEnabled, result.isSmsEnabled());
                    assertEquals(emailEnabled, result.isEmailEnabled());
                    assertEquals(wechatEnabled, result.isWechatEnabled());
                }
            }
        }
    }

    @Test
    void shouldKeepOtherChannelsWhenOneProviderThrows()
    {
        SmsConfigManager smsConfigManager = mock(SmsConfigManager.class);
        MailConfigManager mailConfigManager = mock(MailConfigManager.class);
        WxLoginConfigManager wxLoginConfigManager = mock(WxLoginConfigManager.class);
        when(smsConfigManager.isEnabled()).thenThrow(new IllegalStateException("短信配置异常"));
        when(mailConfigManager.isEnabled()).thenReturn(true);
        when(wxLoginConfigManager.isEnabled()).thenReturn(true);

        PublicConfigVO.LoginChannels result = buildChannels(
                smsConfigManager, mailConfigManager, wxLoginConfigManager);

        assertEquals(false, result.isSmsEnabled());
        assertEquals(true, result.isEmailEnabled());
        assertEquals(true, result.isWechatEnabled());
    }

    private PublicConfigVO.LoginChannels buildChannels(
            boolean smsEnabled, boolean emailEnabled, boolean wechatEnabled)
    {
        SmsConfigManager smsConfigManager = mock(SmsConfigManager.class);
        MailConfigManager mailConfigManager = mock(MailConfigManager.class);
        WxLoginConfigManager wxLoginConfigManager = mock(WxLoginConfigManager.class);
        when(smsConfigManager.isEnabled()).thenReturn(smsEnabled);
        when(mailConfigManager.isEnabled()).thenReturn(emailEnabled);
        when(wxLoginConfigManager.isEnabled()).thenReturn(wechatEnabled);
        return buildChannels(smsConfigManager, mailConfigManager, wxLoginConfigManager);
    }

    private PublicConfigVO.LoginChannels buildChannels(
            SmsConfigManager smsConfigManager,
            MailConfigManager mailConfigManager,
            WxLoginConfigManager wxLoginConfigManager)
    {
        AuthService authService = new AuthService();
        ReflectionTestUtils.setField(authService, "smsConfigManager", smsConfigManager);
        ReflectionTestUtils.setField(authService, "mailConfigManager", mailConfigManager);
        ReflectionTestUtils.setField(authService, "wxLoginConfigManager", wxLoginConfigManager);
        return ReflectionTestUtils.invokeMethod(authService, "buildLoginChannels");
    }
}
