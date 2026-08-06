package com.aid.user.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.auth.util.SilentRegistrationUtils;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.aid.core.service.ISysUserService;
import com.aid.user.dto.AdminUserCreateRequest;
import com.aid.user.vo.AdminUserCreateVO;

/**
 * 后台 C 端用户创建服务测试。
 *
 * @author 视觉AID
 */
class AdminUserCreateServiceImplTest {

    private ISysUserService userService;

    private AdminUserCreateServiceImpl service;

    @BeforeEach
    void setUp() {
        userService = mock(ISysUserService.class);
        service = new AdminUserCreateServiceImpl();
        ReflectionTestUtils.setField(service, "userService", userService);
        when(userService.checkUserNameUnique(any(SysUser.class))).thenReturn(true);
        doAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setUserId(123L);
            return 1;
        }).when(userService).insertUser(any(SysUser.class));
    }

    @Test
    void shouldCreatePhoneUserWithInitialPasswordAndDefaultRole() {
        when(userService.checkPhoneUnique(any(SysUser.class))).thenReturn(true);
        AdminUserCreateRequest request = new AdminUserCreateRequest();
        request.setPhonenumber("13800138000");

        AdminUserCreateVO result = service.createUser(request, "admin");

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userService).insertUser(captor.capture());
        SysUser saved = captor.getValue();
        assertEquals(123L, result.getUserId());
        assertEquals("13800138000", result.getAccount());
        assertEquals("phone", result.getAccountType());
        assertEquals(12, result.getPassword().length());
        assertTrue(result.getPassword().matches(".*[A-Z].*"));
        assertTrue(result.getPassword().matches(".*[a-z].*"));
        assertTrue(result.getPassword().matches(".*[0-9].*"));
        assertTrue(SecurityUtils.matchesPassword(result.getPassword(), saved.getPassword()));
        assertNotEquals(result.getPassword(), saved.getPassword());
        assertEquals("13800138000", saved.getPhonenumber());
        assertNull(saved.getEmail());
        assertEquals(SilentRegistrationUtils.DEFAULT_DEPT_ID, saved.getDeptId());
        assertEquals(SilentRegistrationUtils.DEFAULT_ROLE_ID, saved.getRoleIds()[0]);
        assertEquals("admin", saved.getCreateBy());
    }

    @Test
    void shouldNormalizeAndCreateEmailUser() {
        when(userService.checkEmailUnique(any(SysUser.class))).thenReturn(true);
        AdminUserCreateRequest request = new AdminUserCreateRequest();
        request.setEmail(" Test.User+Aid@Example.COM ");

        AdminUserCreateVO result = service.createUser(request, "admin");

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userService).insertUser(captor.capture());
        assertEquals("test.user+aid@example.com", result.getAccount());
        assertEquals("email", result.getAccountType());
        assertEquals("test.user+aid@example.com", captor.getValue().getEmail());
        assertNull(captor.getValue().getPhonenumber());
    }

    @Test
    void shouldRejectWhenBothContactsAreProvided() {
        AdminUserCreateRequest request = new AdminUserCreateRequest();
        request.setEmail("test@example.com");
        request.setPhonenumber("13800138000");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createUser(request, "admin"));

        assertEquals("联系方式二选一", error.getMessage());
    }

    @Test
    void shouldRejectWhenContactIsMissing() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createUser(new AdminUserCreateRequest(), "admin"));

        assertEquals("联系方式二选一", error.getMessage());
    }

    @Test
    void shouldRejectDuplicatePhone() {
        when(userService.checkPhoneUnique(any(SysUser.class))).thenReturn(false);
        AdminUserCreateRequest request = new AdminUserCreateRequest();
        request.setPhonenumber("13800138000");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createUser(request, "admin"));

        assertEquals("手机号已存在", error.getMessage());
    }
}
