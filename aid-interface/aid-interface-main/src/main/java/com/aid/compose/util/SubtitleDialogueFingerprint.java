package com.aid.compose.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.aid.voice.util.DialogueSubtitleFormatter;

/** 字幕台词指纹：先按成片展示口径格式化，确保前端文本与后端分镜台词使用同一比较结果。 */
public final class SubtitleDialogueFingerprint {

    private static final String PREFIX = "DIALOGUE:";

    private SubtitleDialogueFingerprint() {
    }

    /**
     * 计算格式化台词指纹。
     *
     * @param dialogue 前端字幕或后端分镜台词
     * @return SHA-256 指纹；无有效台词时返回 null
     */
    public static String of(String dialogue) {
        String formatted = DialogueSubtitleFormatter.format(dialogue);
        return StrUtil.isBlank(formatted) ? null : DigestUtil.sha256Hex(PREFIX + formatted);
    }
}
