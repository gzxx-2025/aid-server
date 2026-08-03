package com.aid.promotion.service.impl;

import com.aid.aid.domain.AidInviteCode;
import com.aid.aid.domain.AidInviteRelation;
import com.aid.aid.service.IAidInviteCodeService;
import com.aid.aid.service.IAidInviteRebateRecordService;
import com.aid.aid.service.IAidInviteRelationService;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.exception.ServiceException;
import com.aid.core.service.ISysUserService;
import com.aid.promotion.domain.InviteConfig;
import com.aid.promotion.service.IPromotionConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InviteServiceImplTest {

    private static final Long INVITER_USER_ID = 100L;

    private static final Long INVITEE_USER_ID = 200L;

    private static final String INVITE_CODE = "A2B3C4D5";

    @Mock
    private IPromotionConfigService promotionConfigService;

    @Mock
    private IAidInviteCodeService aidInviteCodeService;

    @Mock
    private IAidInviteRelationService aidInviteRelationService;

    @Mock
    private IAidInviteRebateRecordService aidInviteRebateRecordService;

    @Mock
    private ISysUserService sysUserService;

    private InviteServiceImpl inviteService;

    @BeforeEach
    void setUp() {
        inviteService = new InviteServiceImpl(
                promotionConfigService,
                aidInviteCodeService,
                aidInviteRelationService,
                aidInviteRebateRecordService,
                sysUserService);
    }

    @Test
    void blankCodeDoesNotParticipateInInvitation() {
        inviteService.bindOnRegister(INVITEE_USER_ID, " ", "sms");

        verify(promotionConfigService, never()).getInviteConfig();
        verify(aidInviteRelationService, never()).save(any(AidInviteRelation.class));
    }

    @Test
    void disabledActivityRejectsRegistration() {
        InviteConfig config = new InviteConfig();
        config.setEnabled(false);
        when(promotionConfigService.getInviteConfig()).thenReturn(config);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> inviteService.bindOnRegister(INVITEE_USER_ID, INVITE_CODE, "sms"));

        assertEquals("邀请活动未开启", exception.getMessage());
    }

    @Test
    void invalidCodeFormatRejectsRegistration() {
        mockEnabledActivity();

        ServiceException exception = assertThrows(ServiceException.class,
                () -> inviteService.bindOnRegister(INVITEE_USER_ID, "invalid", "email"));

        assertEquals("邀请码无效", exception.getMessage());
        verify(aidInviteCodeService, never()).getByCode(any(String.class));
    }

    @Test
    void preValidationAcceptsValidCodeWithoutCreatingRelation() {
        mockValidInvitation();

        inviteService.validateForRegistration(" a2b3c4d5 ");

        verify(aidInviteRelationService, never()).save(any(AidInviteRelation.class));
    }

    @Test
    void preValidationRejectsUnknownCode() {
        mockEnabledActivity();
        when(aidInviteCodeService.getByCode(INVITE_CODE)).thenReturn(null);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> inviteService.validateForRegistration(INVITE_CODE));

        assertEquals("邀请码无效", exception.getMessage());
        verify(aidInviteRelationService, never()).save(any(AidInviteRelation.class));
    }

    @Test
    void unknownCodeRejectsRegistration() {
        mockEnabledActivity();
        when(aidInviteCodeService.getByCode(INVITE_CODE)).thenReturn(null);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> inviteService.bindOnRegister(INVITEE_USER_ID, INVITE_CODE, "sms"));

        assertEquals("邀请码无效", exception.getMessage());
    }

    @Test
    void disabledInviterRejectsRegistration() {
        mockEnabledActivity();
        mockInviteCode();
        SysUser inviter = normalUser(INVITER_USER_ID);
        inviter.setStatus("1");
        when(sysUserService.selectUserById(INVITER_USER_ID)).thenReturn(inviter);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> inviteService.bindOnRegister(INVITEE_USER_ID, INVITE_CODE, "wechat"));

        assertEquals("邀请码无效", exception.getMessage());
    }

    @Test
    void validCodeCreatesNormalizedRelation() {
        mockValidInvitation();
        when(aidInviteRelationService.save(any(AidInviteRelation.class))).thenReturn(true);

        inviteService.bindOnRegister(INVITEE_USER_ID, " a2b3c4d5 ", "email");

        ArgumentCaptor<AidInviteRelation> captor = ArgumentCaptor.forClass(AidInviteRelation.class);
        verify(aidInviteRelationService).save(captor.capture());
        AidInviteRelation relation = captor.getValue();
        assertEquals(INVITER_USER_ID, relation.getInviterUserId());
        assertEquals(INVITEE_USER_ID, relation.getInviteeUserId());
        assertEquals(INVITE_CODE, relation.getInviteCode());
        assertEquals("email", relation.getRegisterChannel());
        assertEquals("0", relation.getStatus());
        assertEquals("0", relation.getDelFlag());
    }

    @Test
    void identicalExistingRelationIsIdempotent() {
        mockValidInvitation();
        AidInviteRelation existing = new AidInviteRelation();
        existing.setInviterUserId(INVITER_USER_ID);
        existing.setInviteeUserId(INVITEE_USER_ID);
        existing.setInviteCode(INVITE_CODE);
        when(aidInviteRelationService.getByInvitee(INVITEE_USER_ID)).thenReturn(existing);

        inviteService.bindOnRegister(INVITEE_USER_ID, INVITE_CODE, "sms");

        verify(aidInviteRelationService, never()).save(any(AidInviteRelation.class));
    }

    @Test
    void conflictingExistingRelationRejectsRegistration() {
        mockValidInvitation();
        AidInviteRelation existing = new AidInviteRelation();
        existing.setInviterUserId(999L);
        existing.setInviteeUserId(INVITEE_USER_ID);
        existing.setInviteCode("Z9Y8X7W6");
        when(aidInviteRelationService.getByInvitee(INVITEE_USER_ID)).thenReturn(existing);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> inviteService.bindOnRegister(INVITEE_USER_ID, INVITE_CODE, "sms"));

        assertEquals("邀请关系冲突", exception.getMessage());
        verify(aidInviteRelationService, never()).save(any(AidInviteRelation.class));
    }

    private void mockEnabledActivity() {
        InviteConfig config = new InviteConfig();
        config.setEnabled(true);
        when(promotionConfigService.getInviteConfig()).thenReturn(config);
    }

    private void mockInviteCode() {
        AidInviteCode inviteCode = new AidInviteCode();
        inviteCode.setUserId(INVITER_USER_ID);
        inviteCode.setInviteCode(INVITE_CODE);
        when(aidInviteCodeService.getByCode(INVITE_CODE)).thenReturn(inviteCode);
    }

    private void mockValidInvitation() {
        mockEnabledActivity();
        mockInviteCode();
        when(sysUserService.selectUserById(INVITER_USER_ID)).thenReturn(normalUser(INVITER_USER_ID));
    }

    private SysUser normalUser(Long userId) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setStatus("0");
        user.setDelFlag("0");
        return user;
    }
}
