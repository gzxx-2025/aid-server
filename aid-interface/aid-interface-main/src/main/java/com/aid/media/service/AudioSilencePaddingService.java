package com.aid.media.service;

import java.util.Objects;

import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.oss.entity.UploadResult;
import com.aid.common.oss.factory.OssFactory;
import com.aid.media.util.MediaBytesFetcher;
import com.aid.media.util.WavAudioSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 音频补静音服务：把音频尾部补静音到指定时长并落对象存储，用于对口型等「成片长度跟随驱动音频」的链路。
 * 仅支持 wav 容器（PCM），任何不满足条件的情况都返回 null 由调用方按原音频降级。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AudioSilencePaddingService {

    /** 补齐结果体积上限：32MB 可覆盖 48kHz 立体声约 170 秒，超出说明入参异常 */
    private static final int MAX_PADDED_BYTES = 32 * 1024 * 1024;

    /** 音频落库后缀 */
    private static final String AUDIO_SUFFIX_WAV = ".wav";

    /** 音频 ContentType */
    private static final String AUDIO_CONTENT_TYPE_WAV = "audio/wav";

    @Resource
    private MediaUrlResolver mediaUrlResolver;

    /**
     * 把音频补静音到目标时长并上传对象存储。
     *
     * @param fullUrl          原音频完整可访问 URL
     * @param targetDurationMs 目标时长（毫秒）
     * @return 补齐后音频的完整可访问 URL；无需补齐或无法补齐返回 null
     */
    public String padWithSilence(String fullUrl, int targetDurationMs) {
        if (StrUtil.isBlank(fullUrl) || targetDurationMs <= 0) {
            return null;
        }
        MediaBytesFetcher.Content content = MediaBytesFetcher.fetch(fullUrl);
        if (content.isEmpty()) {
            log.warn("音频补静音跳过: 原音频下载为空, url={}", fullUrl);
            return null;
        }
        // 截断字节的 data 块长度不完整，据此补齐会得到错误时长，直接放弃
        if (content.truncated()) {
            log.warn("音频补静音跳过: 原音频超出下载上限, url={}", fullUrl);
            return null;
        }
        byte[] padded = WavAudioSupport.padWithSilence(content.bytes(), targetDurationMs, MAX_PADDED_BYTES);
        if (Objects.isNull(padded)) {
            return null;
        }
        try {
            UploadResult uploadResult = OssFactory.instance()
                    .uploadSuffix(padded, AUDIO_SUFFIX_WAV, AUDIO_CONTENT_TYPE_WAV);
            String paddedUrl = mediaUrlResolver.toFullUrl(uploadResult.getUrl());
            log.info("音频补静音完成, targetMs={}, originBytes={}, paddedBytes={}",
                    targetDurationMs, content.bytes().length, padded.length);
            return paddedUrl;
        } catch (Exception ex) {
            log.warn("音频补静音上传失败, url={}, err={}", fullUrl, ex.getMessage());
            return null;
        }
    }
}
