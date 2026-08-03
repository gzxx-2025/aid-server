package com.aid.common.aid.sms.utils;

import cn.hutool.core.util.StrUtil;

import java.util.Map;

/**
 * 短信宝响应码解析工具。
 *
 * @author 视觉AID
 */
public final class SmsBaoResponseCodes {

    /** 发送或余额查询成功 */
    private static final String SUCCESS_CODE = "0";

    /** 官方文档定义的响应码 */
    private static final Map<String, String> CODE_MESSAGES = Map.of(
            "0", "操作成功",
            "30", "短信宝密钥错误",
            "40", "短信宝账号不存在",
            "41", "短信宝余额不足",
            "43", "短信宝IP受限",
            "50", "短信内容含敏感词",
            "51", "手机号码不正确"
    );

    private SmsBaoResponseCodes() {
    }

    /**
     * 获取响应首行状态码。
     *
     * @param responseBody 响应正文
     * @return 状态码，正文为空时返回空串
     */
    public static String firstLine(String responseBody) {
        if (StrUtil.isBlank(responseBody)) {
            return "";
        }
        String normalized = responseBody.trim();
        int lineFeedIndex = normalized.indexOf('\n');
        int carriageReturnIndex = normalized.indexOf('\r');
        int newlineIndex;
        if (lineFeedIndex < 0) {
            newlineIndex = carriageReturnIndex;
        } else if (carriageReturnIndex < 0) {
            newlineIndex = lineFeedIndex;
        } else {
            newlineIndex = Math.min(lineFeedIndex, carriageReturnIndex);
        }
        String firstLine = newlineIndex >= 0 ? normalized.substring(0, newlineIndex) : normalized;
        return firstLine.trim();
    }

    /** 判断响应是否成功。 */
    public static boolean isSuccess(String responseBody) {
        return SUCCESS_CODE.equals(firstLine(responseBody));
    }

    /** 返回适合后台展示的响应说明。 */
    public static String describe(String responseBody) {
        String code = firstLine(responseBody);
        if (StrUtil.isBlank(code)) {
            return "短信宝响应为空";
        }
        return CODE_MESSAGES.getOrDefault(code, "短信宝返回错误：" + code);
    }
}
