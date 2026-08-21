package com.aid.rps.enums;

import java.util.Arrays;
import java.util.Optional;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 分镜视频提示词连续性模式。
 *
 * @author 视觉AID
 */
@Getter
@AllArgsConstructor
public enum VideoPromptContinuityMode
{
    NONE("none"),
    PREVIOUS_PROMPT("previous_prompt");

    private final String value;

    /**
     * 按接口值解析连续性模式。
     *
     * @param value 接口值
     * @return 匹配的模式
     */
    public static Optional<VideoPromptContinuityMode> fromValue(String value)
    {
        if (StrUtil.isBlank(value))
        {
            return Optional.of(NONE);
        }
        String normalized = value.trim();
        return Arrays.stream(values())
                .filter(mode -> mode.value.equalsIgnoreCase(normalized))
                .findFirst();
    }
}
