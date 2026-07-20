package com.aid.common.utils.log;

/**
 * 日志脱敏工具。
 * 用于日志打印前对敏感字段做统一处理：验证码、身份证、手机号、邮箱、API Key、
 * 支付交易号等。调用方保证传入的是"将要入日志"的字符串，不要把脱敏结果入库
 * 或作为业务数据继续使用。
 *
 * @author 视觉AID
 */
public final class LogSanitizer
{
    private LogSanitizer() {}

    /**
     * 验证码脱敏：仅保留末 1 位，前面用 * 占位。
     * <pre>
     *   "123456" -> "*****6"
     *   "abc"    -> "**c"
     *   null / ""-> 原样返回
     * </pre>
     */
    public static String maskCode(String code)
    {
        if (code == null || code.isEmpty())
        {
            return code;
        }
        int len = code.length();
        if (len == 1)
        {
            return "*";
        }
        return repeat('*', len - 1) + code.charAt(len - 1);
    }

    /**
     * 手机号脱敏：保留前 3 位 + 后 4 位。
     */
    public static String maskPhone(String phone)
    {
        if (phone == null || phone.length() < 7)
        {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 邮箱脱敏：前缀保留首 1 位，其余用 * 替换。
     */
    public static String maskEmail(String email)
    {
        if (email == null)
        {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 1)
        {
            return email;
        }
        return email.charAt(0) + repeat('*', at - 1) + email.substring(at);
    }

    /**
     * 身份证脱敏：保留前 3 + 后 4。
     */
    public static String maskIdCard(String idCard)
    {
        if (idCard == null || idCard.length() < 7)
        {
            return idCard;
        }
        int len = idCard.length();
        return idCard.substring(0, 3) + repeat('*', len - 7) + idCard.substring(len - 4);
    }

    /**
     * 姓名脱敏：保留首字，其余用 * 替换。
     */
    public static String maskName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return name;
        }
        if (name.length() == 1)
        {
            return "*";
        }
        return name.charAt(0) + repeat('*', name.length() - 1);
    }

    /**
     * API Key / Secret / Token 脱敏：保留首 4 + 末 4，中间用 * 替换。
     * 过短直接全部用 *。
     */
    public static String maskSecret(String secret)
    {
        if (secret == null)
        {
            return null;
        }
        int len = secret.length();
        if (len <= 8)
        {
            return repeat('*', len);
        }
        return secret.substring(0, 4) + repeat('*', len - 8) + secret.substring(len - 4);
    }

    /**
     * OpenID / UnionID 脱敏：仅保留后 6 位。
     */
    public static String maskOpenId(String openId)
    {
        if (openId == null || openId.length() < 7)
        {
            return openId;
        }
        return repeat('*', openId.length() - 6) + openId.substring(openId.length() - 6);
    }

    /**
     * URL 中 querystring 的敏感参数脱敏：signature / access_token / apikey 等 key 名
     * 对应的 value 统一打掩。未命中则原样返回。
     */
    public static String maskUrl(String url)
    {
        if (url == null || url.indexOf('?') < 0)
        {
            return url;
        }
        String head = url.substring(0, url.indexOf('?') + 1);
        String query = url.substring(url.indexOf('?') + 1);
        StringBuilder sb = new StringBuilder(head);
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++)
        {
            String p = pairs[i];
            int eq = p.indexOf('=');
            if (eq > 0)
            {
                String k = p.substring(0, eq).toLowerCase();
                if (k.contains("signature") || k.contains("sign")
                        || k.contains("token") || k.contains("secret")
                        || k.contains("apikey") || k.contains("api_key")
                        || k.contains("accesskey"))
                {
                    sb.append(p, 0, eq + 1).append("***");
                }
                else
                {
                    sb.append(p);
                }
            }
            else
            {
                sb.append(p);
            }
            if (i < pairs.length - 1)
            {
                sb.append('&');
            }
        }
        return sb.toString();
    }

    private static String repeat(char ch, int n)
    {
        if (n <= 0)
        {
            return "";
        }
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, ch);
        return new String(arr);
    }
}
