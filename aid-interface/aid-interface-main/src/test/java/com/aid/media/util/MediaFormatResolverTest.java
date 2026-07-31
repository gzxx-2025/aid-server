package com.aid.media.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 媒体地址扩展名解析用例。
 * 上传登记的格式白名单校验与出片解析的格式下发共用本工具，两侧口径必须一致，
 * 否则会出现「上传放行、出片被剔除」的错位。
 *
 * @author 视觉AID
 */
class MediaFormatResolverTest {

    @Test
    void shouldResolveExtensionFromRelativePath() {
        assertEquals("wav", MediaFormatResolver.resolveFormat("/profile/upload/2026/07/26/a.wav"));
        assertEquals("mp3", MediaFormatResolver.resolveFormat("/upload/audio/b.MP3"));
    }

    @Test
    void shouldResolveExtensionFromFullUrl() {
        assertEquals("wav", MediaFormatResolver.resolveFormat("https://cdn.example.com/upload/a.wav"));
    }

    @Test
    void shouldIgnoreQueryAndFragment() {
        // 只看路径部分，query / fragment 里的点号不算扩展名
        assertEquals("wav", MediaFormatResolver.resolveFormat("https://cdn.example.com/a.wav?v=1.2.3"));
        assertEquals("mp3", MediaFormatResolver.resolveFormat("https://cdn.example.com/a.mp3#t=0.5"));
    }

    @Test
    void shouldReturnNullWhenDotOnlyInDirectory() {
        // 点号在目录名里不构成扩展名，否则会解析出 "0/audio" 这种伪格式
        assertNull(MediaFormatResolver.resolveFormat("/v1.0/audio"));
        assertNull(MediaFormatResolver.resolveFormat("https://cdn.example.com/v1.0/audio"));
    }

    @Test
    void shouldReturnNullWhenExtensionMissing() {
        assertNull(MediaFormatResolver.resolveFormat("/upload/audio"));
        assertNull(MediaFormatResolver.resolveFormat("/upload/audio."));
        assertNull(MediaFormatResolver.resolveFormat(""));
        assertNull(MediaFormatResolver.resolveFormat("   "));
        assertNull(MediaFormatResolver.resolveFormat(null));
    }

    @Test
    void shouldReturnNullWhenUrlIllegal() {
        assertNull(MediaFormatResolver.resolveFormat("http://[bad host]/a.wav"));
    }
}
