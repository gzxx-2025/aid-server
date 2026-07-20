package com.aid.common.config.test;

import lombok.Data;

/**
 * 配置连通性测试结果。
 */
@Data
public class ConfigTestResult {

    /**
     * 是否连通成功。
     */
    private boolean success;

    /**
     * 面向用户的结论文案（建议 ≤30 字中文）。
     */
    private String message;

    /**
     * 调试明细，仅超管可见；非超管须置 null。
     */
    private String details;

    /**
     * 耗时（毫秒）。
     */
    private long elapsedMs;

    /**
     * 实际命中的厂商，可空。
     */
    private String provider;

    /**
     * 构造成功结果。
     *
     * @param message 结论文案
     * @return 成功结果
     */
    public static ConfigTestResult ok(String message) {
        ConfigTestResult result = new ConfigTestResult();
        result.setSuccess(true);
        result.setMessage(message);
        return result;
    }

    /**
     * 构造成功结果（带厂商）。
     *
     * @param message  结论文案
     * @param provider 实际命中的厂商
     * @return 成功结果
     */
    public static ConfigTestResult ok(String message, String provider) {
        ConfigTestResult result = ok(message);
        result.setProvider(provider);
        return result;
    }

    /**
     * 构造失败结果。
     *
     * @param message 结论文案
     * @return 失败结果
     */
    public static ConfigTestResult fail(String message) {
        ConfigTestResult result = new ConfigTestResult();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }
}
