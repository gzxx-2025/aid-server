package com.aid.common.aid.real.entity;

import lombok.Data;

/**
 * 实名认证结果
 *
 * @author 视觉AID
 */
@Data
public class RealAuthResult {

    /**
     * 是否认证通过
     */
    private Boolean success;

    /**
     * 结果代码
     */
    private String code;

    /**
     * 结果消息
     */
    private String message;

    public static RealAuthResult success() {
        RealAuthResult result = new RealAuthResult();
        result.setSuccess(true);
        result.setCode("0");
        result.setMessage("认证通过");
        return result;
    }

    public static RealAuthResult fail(String message) {
        return fail("-1", message);
    }

    public static RealAuthResult fail(String code, String message) {
        RealAuthResult result = new RealAuthResult();
        result.setSuccess(false);
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
