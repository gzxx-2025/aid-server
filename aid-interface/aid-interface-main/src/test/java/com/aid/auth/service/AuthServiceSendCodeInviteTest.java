package com.aid.auth.service;

import com.aid.auth.domain.dto.SendCodeRequest;
import com.aid.auth.policy.AuthCodePolicyService;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.exception.ServiceException;
import com.aid.core.service.ISysUserService;
import com.aid.promotion.service.IInviteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceSendCodeInviteTest {

    private static final String PHONE = "13888888888";

    private static final String INVITE_CODE = "A2B3C4D5";

    @Mock
    private ISysUserService userService;

    @Mock
    private IInviteService inviteService;

    @Mock
    private AuthCodePolicyService authCodePolicyService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService();
        ReflectionTestUtils.setField(authService, "userService", userService);
        ReflectionTestUtils.setField(authService, "inviteService", inviteService);
        ReflectionTestUtils.setField(authService, "authCodePolicyService", authCodePolicyService);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void invalidInviteCodeIsRejectedBeforeRateLimitAndProviderCall() {
        SendCodeRequest request = loginSmsRequest();
        when(userService.selectUserByPhonenumber(PHONE)).thenReturn(null);
        doThrow(new ServiceException("邀请码无效"))
                .when(inviteService).validateForRegistration(INVITE_CODE);

        ServiceException exception = assertThrows(ServiceException.class, () -> authService.sendCode(request));

        assertEquals("邀请码无效", exception.getMessage());
        verify(authCodePolicyService, never()).getPolicy("sms");
    }

    @Test
    void existingUserLoginIgnoresInviteCode() {
        SendCodeRequest request = loginSmsRequest();
        SysUser existingUser = new SysUser();
        existingUser.setUserId(1L);
        when(userService.selectUserByPhonenumber(PHONE)).thenReturn(existingUser);
        doThrow(new ServiceException("停止测试")).when(authCodePolicyService).getPolicy("sms");

        ServiceException exception = assertThrows(ServiceException.class, () -> authService.sendCode(request));

        assertEquals("停止测试", exception.getMessage());
        verify(inviteService, never()).validateForRegistration(INVITE_CODE);
    }

    private SendCodeRequest loginSmsRequest() {
        SendCodeRequest request = new SendCodeRequest();
        request.setTarget(PHONE);
        request.setCodeType("sms");
        request.setScene("login");
        request.setInviteCode(INVITE_CODE);
        return request;
    }
}
