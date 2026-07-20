package com.aid.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户引导 Tour 状态枚举
 *
 * @author 视觉AID
 */
@Getter
@AllArgsConstructor
public enum OnboardingTourStatus {

    /** 已完成 */
    COMPLETED("completed"),

    /** 已跳过 */
    SKIPPED("skipped"),

    /** 进行中 */
    IN_PROGRESS("in_progress");

    private final String code;

    /**
     * 根据 code 解析枚举，非法值返回 null
     */
    public static OnboardingTourStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (OnboardingTourStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 返回状态优先级（冲突合并用：completed > skipped > in_progress）。
     */
    public int getPriority() {
        switch (this) {
            case COMPLETED:
                return 3;
            case SKIPPED:
                return 2;
            case IN_PROGRESS:
                return 1;
            default:
                return 0;
        }
    }
}
