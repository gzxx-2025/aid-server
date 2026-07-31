package com.aid.common.moderation;

/**
 * 图片审查命中决策
 *
 * @author 视觉AID
 */
public enum ModerationDecision
{
    /**
     * 通过，放行
     */
    PASS,

    /**
     * 拦截，禁止
     */
    BLOCK,

    /**
     * 建议复审
     */
    REVIEW,

    /**
     * 审查异常
     */
    ERROR
}
