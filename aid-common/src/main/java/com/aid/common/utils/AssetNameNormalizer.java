package com.aid.common.utils;

import java.text.Normalizer;

/**
 * 资产名称业务键规范化工具。
 *
 * @author 视觉AID
 */
public final class AssetNameNormalizer
{
    /** 资产展示名称数据库字段最大字符数。 */
    public static final int MAX_DISPLAY_LENGTH = 100;

    /** 规范化名称数据库字段最大字符数。 */
    public static final int MAX_NORMALIZED_LENGTH = 255;

    private AssetNameNormalizer()
    {
    }

    /**
     * 生成不影响展示名称的稳定业务键。
     *
     * @param name 展示名称
     * @return 规范化业务键
     */
    public static String normalize(String name)
    {
        if (name == null)
        {
            return "";
        }
        String compatibilityNormalized = Normalizer.normalize(name, Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(compatibilityNormalized.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < compatibilityNormalized.length();)
        {
            int codePoint = compatibilityNormalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))
            {
                pendingSpace = result.length() > 0;
                continue;
            }
            if (pendingSpace)
            {
                result.append(' ');
                pendingSpace = false;
            }
            if (codePoint >= 'A' && codePoint <= 'Z')
            {
                codePoint += 'a' - 'A';
            }
            result.appendCodePoint(codePoint);
        }
        return result.toString();
    }
}
