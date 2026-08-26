package com.aid.script.helper;

import cn.hutool.crypto.digest.DigestUtil;

/**
 * 计算剧本正文的稳定哈希。
 *
 * @author 视觉AID
 */
public final class UserScriptContentHash
{
    private UserScriptContentHash()
    {
    }

    public static String calculate(String content)
    {
        return DigestUtil.sha256Hex(content == null ? "" : content);
    }
}
