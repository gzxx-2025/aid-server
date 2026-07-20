package com.aid.voice.service;

import org.springframework.stereotype.Service;

import com.aid.aid.service.IAidConfigService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 配音试听限制服务。
 * 统一读取后台试听秒数配置并换算前端可展示、后端可校验的最大字数，避免两处口径不一致。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoicePreviewLimitService
{
    /** 试听秒数配置分类 */
    private static final String CONFIG_CATEGORY_VOICE = "voice";

    /** 试听秒数配置名 */
    private static final String CONFIG_KEY_PREVIEW_MAX_SECONDS = "voice_preview_max_seconds";

    /** 中文 TTS 正常语速估算（字/秒） */
    private static final double CHARS_PER_SECOND = 4.5D;

    /** 配置缺失或非法时的最大字数兜底 */
    private static final int DEFAULT_MAX_CHARS = 15;

    private final IAidConfigService aidConfigService;

    /**
     * 获取当前试听限制。
     *
     * @return 秒数配置与换算后的最大字数；配置缺失时 maxSeconds 为 null，最大字数仍返回兜底值
     */
    public Limit getLimit()
    {
        try
        {
            String value = aidConfigService.getConfigValue(
                    CONFIG_CATEGORY_VOICE, CONFIG_KEY_PREVIEW_MAX_SECONDS);
            if (StrUtil.isBlank(value))
            {
                return fallback();
            }
            int maxSeconds = Integer.parseInt(value.trim());
            if (maxSeconds <= 0)
            {
                log.info("试听秒数配置非法, value={}", value);
                return fallback();
            }
            // 秒数换算字数时向下取整，保证实际朗读时长不超过配置值
            int estimatedMaxChars = Math.max(1, (int) Math.floor(maxSeconds * CHARS_PER_SECOND));
            return new Limit(maxSeconds, estimatedMaxChars);
        }
        catch (Exception e)
        {
            log.error("试听限制读取失败，按{}字兜底", DEFAULT_MAX_CHARS, e);
            return fallback();
        }
    }

    private Limit fallback()
    {
        return new Limit(null, DEFAULT_MAX_CHARS);
    }

    /**
     * 试听限制快照。
     *
     * @param maxSeconds 后台配置的最大试听秒数；配置缺失或非法时为 null
     * @param estimatedMaxChars 按正常语速换算且实际执行校验的最大字数
     */
    public record Limit(Integer maxSeconds, int estimatedMaxChars)
    {
    }
}
