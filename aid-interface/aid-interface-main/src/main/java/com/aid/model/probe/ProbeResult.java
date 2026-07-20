package com.aid.model.probe;

import lombok.Data;

/**
 * Provider 探活结果。
 *
 * 仅承载「是否连通」的结论，不携带任何生成内容，也不代表真实业务调用成功。
 */
@Data
public class ProbeResult {

    /** 是否探活成功（凭证 + 网关连通） */
    private boolean ok;

    /** 面向用户的结论文案（建议 ≤30 字中文） */
    private String message;

    /** 调试明细（仅超管可见，禁止写入密钥明文） */
    private String detail;

    /**
     * 构造成功结果。
     *
     * @param message 结论文案
     * @return 探活成功结果
     */
    public static ProbeResult ok(String message) {
        ProbeResult result = new ProbeResult();
        result.setOk(true);
        result.setMessage(message);
        return result;
    }

    /**
     * 构造失败结果。
     *
     * @param message 结论文案
     * @param detail  调试明细（无密钥明文）
     * @return 探活失败结果
     */
    public static ProbeResult fail(String message, String detail) {
        ProbeResult result = new ProbeResult();
        result.setOk(false);
        result.setMessage(message);
        result.setDetail(detail);
        return result;
    }
}
