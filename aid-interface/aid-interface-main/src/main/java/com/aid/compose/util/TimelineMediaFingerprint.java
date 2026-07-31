package com.aid.compose.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;

import java.net.URI;
import java.util.List;

/**
 * 时间轴媒体来源指纹。生成记录ID优先，手动素材回落 URL 路径，避免签名参数变化导致误失效。
 *
 * @author 视觉AID
 */
public final class TimelineMediaFingerprint {

    private TimelineMediaFingerprint() {
    }

    public static String of(Long genRecordId, String mediaUrl) {
        if (genRecordId != null) {
            return DigestUtil.sha256Hex("GEN_RECORD:" + genRecordId);
        }
        if (StrUtil.isBlank(mediaUrl)) {
            return null;
        }
        return DigestUtil.sha256Hex("MEDIA_URL:" + normalizeUrl(mediaUrl));
    }

    /** 多段连续媒体按顺序生成稳定指纹，签名参数变化不会误判为换源。 */
    public static String ofGroup(List<String> mediaUrls) {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return null;
        }
        if (mediaUrls.size() == 1) {
            return of(null, mediaUrls.get(0));
        }
        StringBuilder source = new StringBuilder("MEDIA_GROUP:");
        for (String mediaUrl : mediaUrls) {
            if (StrUtil.isBlank(mediaUrl)) {
                return null;
            }
            String normalized = normalizeUrl(mediaUrl);
            source.append(normalized.length()).append(':').append(normalized).append(';');
        }
        return DigestUtil.sha256Hex(source.toString());
    }

    private static String normalizeUrl(String mediaUrl) {
        String normalized = mediaUrl.trim();
        try {
            URI uri = URI.create(normalized);
            if (StrUtil.isNotBlank(uri.getPath())) {
                normalized = uri.getPath();
            }
        } catch (Exception ignored) {
            // 相对对象路径直接参与指纹。
        }
        return normalized;
    }
}
