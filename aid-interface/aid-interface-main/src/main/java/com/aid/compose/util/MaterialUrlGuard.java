package com.aid.compose.util;

import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;

/**
 * 合成素材链接结构守卫：识别不可能被服务端/上游访问到的非法素材地址。
 * 典型场景：前端把浏览器本地临时地址（{@code blob:http://localhost:3001/…}）误当相对路径
 * 拼上 CDN 域名后传给后端，形成 {@code https://cdn.xxx/blob:http://localhost:3001/…} 这类
 * 嵌套地址——该文件从未上传对象存储，合成必然失败，必须在受理入口直接拒绝。
 *
 * @author 视觉AID
 */
public final class MaterialUrlGuard {

    /** blob:/data: 地址（出现在开头或路径段起始处均视为非法，覆盖被误拼进路径的场景） */
    private static final Pattern LOCAL_SCHEME_PATTERN = Pattern.compile("(?i)(^|/)(blob|data):");

    private MaterialUrlGuard() {
    }

    /**
     * 判断素材链接是否为非法结构（blob:/data: 地址、非 http(s) 协议、嵌套协议头）。
     * 空值与普通相对路径返回 false（是否必填由调用方各自校验）。
     *
     * @param url 素材链接（相对路径或完整 URL）
     * @return true=非法结构，必须拒绝
     */
    public static boolean isForbidden(String url) {
        if (StrUtil.isBlank(url)) {
            return false;
        }
        String lower = url.trim().toLowerCase();
        // 浏览器临时对象地址 / 内联数据地址：服务端与上游厂商都无法访问
        if (LOCAL_SCHEME_PATTERN.matcher(lower).find()) {
            return true;
        }
        int firstScheme = lower.indexOf("://");
        if (firstScheme < 0) {
            // 无协议头：普通相对路径，交由后续白名单/可达性校验
            return false;
        }
        // 带协议头时仅允许 http(s)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return true;
        }
        // 同一条链接出现第二个协议头：属于地址误拼接（嵌套 URL）
        return lower.indexOf("://", firstScheme + 3) >= 0;
    }
}
