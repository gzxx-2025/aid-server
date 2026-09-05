package com.aid.auth.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 当前账号安全状态。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AccountSecurityVO {

    private boolean passwordSet;
    private boolean phoneBound;
    private String maskedPhone;
    private boolean emailBound;
    private String maskedEmail;
    private boolean wechatBound;
    private List<String> loginMethods;
    private boolean canUnbindPhone;
    private boolean canUnbindEmail;
    private boolean canUnbindWechat;
    private boolean smsAvailable;
    private boolean emailAvailable;
    private boolean wechatAvailable;
    private boolean reRegistrationRestrictionEnabled;
    private int reRegistrationRestrictionDays;
    private PasswordPolicyVO passwordPolicy;
}
