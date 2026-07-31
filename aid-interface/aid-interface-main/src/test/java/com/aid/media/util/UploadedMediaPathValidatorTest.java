package com.aid.media.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传媒体路径合法性校验用例。
 * {@code @MediaUrl} 只剥离本站配置域名，站外地址会原样落到业务层，
 * 这里守住的是「服务端只会去拉本站资源」这条边界。
 *
 * @author 视觉AID
 */
class UploadedMediaPathValidatorTest {

    @Test
    void shouldAcceptSiteRelativePath() {
        assertTrue(UploadedMediaPathValidator.isLegalRelativePath("/profile/upload/2026/07/26/a.wav"));
        assertTrue(UploadedMediaPathValidator.isLegalRelativePath("/upload/audio/b.mp3"));
    }

    @Test
    void shouldRejectAbsoluteExternalUrl() {
        // @MediaUrl 没能剥掉域名，说明这不是本站资源
        assertFalse(UploadedMediaPathValidator.isLegalRelativePath("http://evil.com/a.wav"));
        assertFalse(UploadedMediaPathValidator.isLegalRelativePath("https://evil.com/a.wav"));
        assertFalse(UploadedMediaPathValidator.isLegalRelativePath("HTTPS://evil.com/a.wav"));
    }

    @Test
    void shouldRejectProtocolRelativeUrl() {
        // //evil.com 会被 HTTP 客户端补成 https://evil.com
        assertFalse(UploadedMediaPathValidator.isLegalRelativePath("//evil.com/a.wav"));
    }

    @Test
    void shouldRejectBackslashVariant() {
        // 部分客户端会把 /\evil.com 归一化成 //evil.com
        assertFalse(UploadedMediaPathValidator.isLegalRelativePath("/\\evil.com/a.wav"));
    }

    @Test
    void shouldRejectPathTraversal() {
        assertFalse(UploadedMediaPathValidator.isLegalRelativePath("/profile/../../etc/passwd"));
        assertFalse(UploadedMediaPathValidator.isLegalRelativePath("/profile/upload/..%2fa.wav"));
    }

    @Test
    void shouldRejectNonRootedOrBlankPath() {
        assertFalse(UploadedMediaPathValidator.isLegalRelativePath("profile/upload/a.wav"));
        assertFalse(UploadedMediaPathValidator.isLegalRelativePath(""));
        assertFalse(UploadedMediaPathValidator.isLegalRelativePath("   "));
        assertFalse(UploadedMediaPathValidator.isLegalRelativePath(null));
    }
}
