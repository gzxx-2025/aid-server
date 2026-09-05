package com.aid.auth.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 密码规则。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class PasswordPolicyVO {

    private int minLength;
    private int maxLength;
}
