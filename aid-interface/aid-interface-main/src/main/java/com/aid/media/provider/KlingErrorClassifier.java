package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;

/** 可灵 HTTP/业务码分类。严禁将暂时性查询异常映射成生成失败。 */
public final class KlingErrorClassifier {

    private KlingErrorClassifier() {
    }

    public static boolean isSuccess(int httpStatus, int businessCode) {
        return httpStatus >= 200 && httpStatus < 300 && businessCode == 0;
    }

    public static boolean isRetryable(int httpStatus, int businessCode) {
        return httpStatus >= 500 || businessCode == 1302 || businessCode == 1303
            || businessCode == 5000 || businessCode == 5001 || businessCode == 5002;
    }

    public static boolean isContentRejected(int businessCode) {
        return businessCode == 1300 || businessCode == 1301;
    }

    /** 面向任务/管理员的安全文案，不包含响应原文、密钥或供应商细节。 */
    public static String safeMessage(int httpStatus, int businessCode) {
        return safeMessage(httpStatus, businessCode, null);
    }

    /**
     * 面向任务/管理员的安全文案。兼容代理只返回 message、未返回官方业务码的情况。
     */
    public static String safeMessage(int httpStatus, int businessCode, String upstreamMessage) {
        String message = StrUtil.trimToEmpty(upstreamMessage).toLowerCase();
        if (isContentRejected(businessCode)
            || containsAny(message, "content safety", "content policy", "safety policy",
                "blocked by", "policy violation", "risk control", "moderation")) {
            return "输入内容未通过安全校验";
        }
        if (httpStatus == 401 || (businessCode >= 1000 && businessCode <= 1004)) {
            return "上游鉴权配置无效";
        }
        if ((businessCode >= 1100 && businessCode <= 1103)
            || businessCode == 1304 || httpStatus == 403) {
            return "上游账户或权限不可用";
        }
        if (businessCode >= 1200 && businessCode <= 1203) {
            return "上游请求参数不兼容";
        }
        if (businessCode == 1302 || businessCode == 1303 || httpStatus == 429) {
            return "上游繁忙，请稍后重试";
        }
        if (isRetryable(httpStatus, businessCode)) {
            return "上游服务暂不可用";
        }
        return "上游请求失败";
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
