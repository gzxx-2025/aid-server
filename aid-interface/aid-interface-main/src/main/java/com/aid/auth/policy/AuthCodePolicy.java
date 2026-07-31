package com.aid.auth.policy;

import lombok.Builder;
import lombok.Getter;

/**
 * 验证码策略配置（短信 / 邮箱通用结构）—— 不可变值对象。
 *
 * @author 视觉AID
 */
@Getter
@Builder
public class AuthCodePolicy {

    /** 渠道：sms / email */
    private final String channel;

    /** 验证码长度（位） */
    private final int codeLength;

    /** 验证码有效期（分钟），即写入 Redis 的 TTL */
    private final int codeExpireMinutes;

    /** 同 target / 同 IP 两次发送之间的最小间隔（秒） */
    private final int sendIntervalSeconds;

    /** 同 target / 同 IP 每自然日最多发送次数（不限：传 ≤ 0） */
    private final int dailyLimit;
}
