package com.aid.auth.domain.vo;

import java.util.Map;

import com.aid.common.aid.oss.vo.OssUploadLimitsVO;
import com.aid.notify.wechat.vo.WechatNotifyPublicVO;

import lombok.Builder;
import lombok.Data;

/**
 * C 端首屏公开配置聚合 VO。
 * 将行为验证码状态、短信/邮箱验证码策略、接口加密状态、基础配置、支付渠道开关等匿名配置
 * 合并为一个 {@code POST /auth/public-config}，前端在登录/发码页加载时一次性获取全部 UI 渲染所需配置。
 * 服务端 Redis 缓存 30s，aid_config 改动后最多 30s 内生效。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class PublicConfigVO
{
    /** 行为验证码块（来自 BehaviorCaptchaController#status 的等价口径） */
    private CaptchaStatus captcha;

    /** 短信验证码策略（aid_config category=sms） */
    private CodePolicy smsPolicy;

    /** 邮箱验证码策略（aid_config category=mail） */
    private CodePolicy emailPolicy;

    /** 服务端时间戳（毫秒），便于前端排查时差与缓存命中调试 */
    private Long serverTime;

    /** 接口加密状态块（来自 aid_config category=api_crypto） */
    private CryptoStatus crypto;

    /** 微信公众号模板消息推送公开状态 */
    private WechatNotifyPublicVO wechatNotify;

    /**
     * 基础配置（来自 aid_config category=basic）。
     * App 上架合规 / 首屏展示所需的公开内容，以键值对动态下发，后台 aid_config 可配置，例如：
     * personal_information_collection_list（个人信息收集清单）、app_permissions_description（应用权限说明）、
     * third_party_sdk_and_information_sharing_list（第三方SDK及信息共享清单）、terms_of_service（用户协议）、
     * privacy_policy（隐私政策）、version_number（版本号）、record_filing_number（备案号）、
     * exchange_image_url（交流二维码图）等。后台新增同分类配置项即自动随接口下发。
     */
    private Map<String, String> basic;

    /**
     * 支付渠道开关（来自 aid_config alipay/wxpay 的 enabled，经"同步配置"后生效的内存口径）。
     * 后台可分别开关支付宝 / 微信支付，C 端据此动态显示可用的支付方式，避免用户选到已关闭的渠道下单失败。
     */
    private PaymentChannels payment;

    /**
     * 上传大小限制（来自 aid_config category=oss 的分类型上传配置）。
     * 含各资源类型（图片/视频/音频/文档等）的单文件大小上限与允许扩展名，以及未配置分类型时的全局兜底上限。
     * C 端据此在上传前按文件类型提示/校验单文件大小，与后台「文件存储 → 上传大小限制」配置保持一致。
     */
    private OssUploadLimitsVO upload;

    /**
     * 配音试听限制（来自 aid_config category=voice）。
     * 前端使用 estimatedMaxChars 展示字数上限并设置输入框 maxLength，与后端试听校验保持一致。
     */
    private VoicePreviewConfig voicePreview;

    @Data
    @Builder
    public static class CaptchaStatus
    {
        /** 行为验证码是否开启；false 时前端跳过验证码直接调登录/发码 */
        private boolean enabled;

        /** 当前类型：SLIDER/ROTATE/WORD_IMAGE_CLICK/CONCAT；enabled=false 时为 null */
        private String type;

        /** 未开启原因（运维诊断；正常为 OK） */
        private String reason;

        /** 远程背景图配置数量 */
        private int urlCount;

        /** 本地兜底背景图数量 */
        private int localCount;

        /** tianai 应用是否构建成功 */
        private boolean applicationOk;

        /** 背景图是否加载就绪 */
        private boolean imagesReady;
    }

    @Data
    @Builder
    public static class CodePolicy
    {
        /** 渠道：sms / email */
        private String channel;

        /** 验证码长度（位），驱动前端输入框 maxLength */
        private int codeLength;

        /** 验证码有效期（分钟） */
        private int codeExpireMinutes;

        /** 重发倒计时秒数 */
        private int sendIntervalSeconds;

        /** 同 target/IP 每日上限（≤0 表示不限） */
        private int dailyLimit;
    }

    /**
     * 接口加密状态块。
     * 前端据 {@link #enabled} 决定是否对后续业务接口做信封加密；开启时用 {@link #publicKey} 加密一次性 AES 密钥。
     */
    @Data
    @Builder
    public static class CryptoStatus
    {
        /** 接口加密是否开启；false 时前端走明文 */
        private boolean enabled;

        /** RSA 公钥（X509，Base64）；enabled=false 或未配置时为 null */
        private String publicKey;

        /** 算法标识，固定 RSA-OAEP-SHA256 + AES-GCM-256，供前端对齐 WebCrypto 参数 */
        private String algorithm;
    }

    /**
     * 支付渠道开关块。
     * 前端据此决定收银台展示哪些支付方式；两个都为 false 时应隐藏支付入口或提示暂不可用。
     */
    @Data
    @Builder
    public static class PaymentChannels
    {
        /** 支付宝是否可用 */
        private boolean alipayEnabled;

        /** 微信支付是否可用 */
        private boolean wxpayEnabled;
    }

    /**
     * 配音试听限制块。
     */
    @Data
    @Builder
    public static class VoicePreviewConfig
    {
        /** 后台配置的最大试听秒数；配置缺失或非法时为 null */
        private Integer maxSeconds;

        /** 按正常语速预估且后端实际执行校验的最大字数 */
        private int estimatedMaxChars;
    }
}
