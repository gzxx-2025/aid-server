package com.aid.media.util;

import cn.hutool.core.util.StrUtil;

/**
 * 用户上传媒体路径合法性校验工具。
 * 用户上传类接口只接受「本站已上传资源的相对路径」——请求 DTO 上的
 * {@code @MediaUrl} 只会剥离本站配置域名，站外链接会原样保留，因此必须在业务层
 * 再判一次，否则用户可传任意外链让服务端去拉取。
 *
 * @author 视觉AID
 */
public final class UploadedMediaPathValidator
{
    private UploadedMediaPathValidator()
    {
    }

    /**
     * 判断是否为合法的本站相对路径。
     * 拒绝：站外绝对 URL、协议相对地址（{@code //evil.com}）、反斜杠变体（{@code /\evil.com}）、
     * 路径穿越（{@code ..}）、空值。
     *
     * @param relativeUrl 待校验路径
     * @return 合法返回 true
     */
    public static boolean isLegalRelativePath(String relativeUrl)
    {
        if (StrUtil.isBlank(relativeUrl))
        {
            return false;
        }
        // 站外绝对 URL：@MediaUrl 未能剥离，说明域名不属于本站
        if (StrUtil.startWithIgnoreCase(relativeUrl, "http://")
                || StrUtil.startWithIgnoreCase(relativeUrl, "https://"))
        {
            return false;
        }
        return StrUtil.startWith(relativeUrl, "/")          // 必须是相对路径
                && !StrUtil.startWith(relativeUrl, "//")     // 协议相对URL（//evil.com）
                && !StrUtil.startWith(relativeUrl, "/\\")    // 反斜杠变体（/\evil.com）
                && !relativeUrl.contains("..");              // 路径穿越（/../）
    }
}
