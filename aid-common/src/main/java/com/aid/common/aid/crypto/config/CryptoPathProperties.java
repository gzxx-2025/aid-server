package com.aid.common.aid.crypto.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 接口加解密“路径策略”配置。
 *
 * @author 视觉AID
 */
@Data
@ConfigurationProperties(prefix = "aid.api-crypto.path")
public class CryptoPathProperties {

    /**
     * 需要加解密的 C 端业务路径（白名单之外全部加密）。
     */
    private List<String> includePatterns = new ArrayList<>(List.of(
            "/api/**",      // C 端业务接口
            "/auth/**",     // 认证（登录/注册/找回/绑定等）
            "/recharge/**", // 充值/订单
            "/realAuth/**", // 实名认证
            "/captcha/**"   // 行为验证码（gen 由下方 exclude 单独豁免）
    ));

    /**
     * 豁免（不加解密）路径，优先级高于 include。
     */
    private List<String> excludePatterns = new ArrayList<>(List.of(
            "/auth/crypto/public-key",      // 公钥下发：引导接口，必须明文
            "/captcha/gen",                 // 滑块验证码生成：仅 gen 返回 base64 图，豁免
            "/auth/wechat/**",              // 微信扫码登录/绑定/回调：含二维码与第三方回调
            "/pay/notify/**",               // 支付回调：第三方调用，不能加密
            "/api/callback/**",             // 媒体厂商回调：第三方调用
            "/api/media/callback/**",       // 媒体任务回调(Vidu/MPS等)：第三方明文回调，不能加密
            "/api/user/oss/**",             // OSS 文件上传/下载（含签名直传）
            "/api/user/task/stream/**",     // SSE 任务进度推送：流式，不加密
            "/v3/api-docs/**",              // Swagger api-docs
            "/swagger-ui/**",               // Swagger UI
            "/swagger-ui.html",
            "/druid/**",                    // Druid 监控
            "/profile/**"                   // 静态资源
    ));
}
