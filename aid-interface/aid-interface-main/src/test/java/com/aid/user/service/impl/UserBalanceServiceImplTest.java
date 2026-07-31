package com.aid.user.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aid.aid.domain.AidUserProfile;
import com.aid.aid.service.IAidUserProfileService;
import com.aid.common.exception.ServiceException;
import com.aid.user.vo.UserBalanceVO;

/**
 * 用户账户积分快捷查询测试。
 *
 * @author 视觉AID
 */
class UserBalanceServiceImplTest {

    private static final Long USER_ID = 100L;

    private IAidUserProfileService aidUserProfileService;

    private UserBalanceServiceImpl service;

    @BeforeEach
    void setUp() {
        aidUserProfileService = mock(IAidUserProfileService.class);
        service = new UserBalanceServiceImpl();
        ReflectionTestUtils.setField(service, "aidUserProfileService", aidUserProfileService);
    }

    @Test
    void shouldReturnAllAccountAmounts() {
        AidUserProfile profile = new AidUserProfile();
        profile.setBalance(new BigDecimal("88.50"));
        profile.setFrozenBalance(new BigDecimal("12.25"));
        profile.setTotalRecharge(new BigDecimal("200.00"));
        profile.setTotalConsumption(new BigDecimal("99.25"));
        when(aidUserProfileService.getAccountBalanceByUserId(USER_ID)).thenReturn(profile);

        UserBalanceVO result = service.getBalance(USER_ID);

        assertEquals(new BigDecimal("88.50"), result.getBalance());
        assertEquals(new BigDecimal("12.25"), result.getFrozenBalance());
        assertEquals(new BigDecimal("200.00"), result.getTotalRecharge());
        assertEquals(new BigDecimal("99.25"), result.getTotalConsumption());
        verify(aidUserProfileService).getAccountBalanceByUserId(USER_ID);
    }

    @Test
    void shouldReturnZeroWhenAccountProfileIsMissing() {
        when(aidUserProfileService.getAccountBalanceByUserId(USER_ID)).thenReturn(null);

        UserBalanceVO result = service.getBalance(USER_ID);

        assertEquals(BigDecimal.ZERO, result.getBalance());
        assertEquals(BigDecimal.ZERO, result.getFrozenBalance());
        assertEquals(BigDecimal.ZERO, result.getTotalRecharge());
        assertEquals(BigDecimal.ZERO, result.getTotalConsumption());
    }

    @Test
    void shouldConvertHistoricalNullAmountsToZero() {
        AidUserProfile profile = new AidUserProfile();
        when(aidUserProfileService.getAccountBalanceByUserId(USER_ID)).thenReturn(profile);

        UserBalanceVO result = service.getBalance(USER_ID);

        assertEquals(BigDecimal.ZERO, result.getBalance());
        assertEquals(BigDecimal.ZERO, result.getFrozenBalance());
        assertEquals(BigDecimal.ZERO, result.getTotalRecharge());
        assertEquals(BigDecimal.ZERO, result.getTotalConsumption());
    }

    @Test
    void shouldRejectMissingLoginUser() {
        assertThrows(ServiceException.class, () -> service.getBalance(null));
    }
}
