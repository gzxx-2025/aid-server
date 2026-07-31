package com.aid.framework.interceptor;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.alibaba.fastjson2.JSON;
import com.aid.common.aid.crypto.core.CryptoKeyHolder;
import com.aid.common.aid.crypto.core.EnvelopeCryptoTemplate;
import com.aid.common.captcha.annotation.CaptchaRequired;
import com.aid.common.captcha.config.CaptchaProperties;
import com.aid.common.captcha.service.CaptchaConfigService;
import com.aid.common.captcha.service.CaptchaTokenService;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.filter.RepeatedlyRequestWrapper;
import com.aid.common.utils.ServletUtils;
import com.aid.common.utils.http.HttpHelper;
import com.aid.framework.crypto.ApiCryptoInterceptor;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 行为验证码拦截器。
 *
 * <p>对带 {@link CaptchaRequired} 的请求做前置人机校验：仅当「开启 + 就绪 + 场景受保护」三者
 * 同时满足时才校验请求头 {@code captcha-token}；校验通过即删除 token（一次性，防重放）后放行。
 * 任何配置读取异常一律放行（fail-open），避免拖垮受保护接口可用性。</p>
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class CaptchaInterceptor implements HandlerInterceptor {

    @Resource
    private CaptchaConfigService captchaConfigService;

    @Resource
    private CaptchaTokenService captchaTokenService;

    /** 信封加密模板：接口加密开启时，豁免判定前需先解密请求体才能解析 loginType */
    @Resource
    private EnvelopeCryptoTemplate envelopeCryptoTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 非控制器方法直接放行
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        Method method = ((HandlerMethod) handler).getMethod();
        CaptchaRequired annotation = method.getAnnotation(CaptchaRequired.class);
        // 未标注注解放行
        if (annotation == null) {
            return true;
        }

        String scene = annotation.scene();
        try {
            // 未开启 / 未就绪（无背景图降级）/ 场景未受保护 → 放行
            if (!captchaConfigService.isEnabled()
                    || !captchaConfigService.isReady()
                    || !captchaConfigService.isSceneProtected(scene)) {
                return true;
            }
        } catch (Exception e) {
            // fail-open：配置异常按放行处理并留痕
            log.error("验证码配置判定异常,放行: scene={}", scene, e);
            return true;
        }

        // 登录场景：短信/邮箱验证码登录已在发码(sendCode)环节完成人机校验且验证码一次性；微信扫码登录无法弹验证码，
        // 这三类登录在登录环节均豁免行为验证码（仅密码登录无发码前置，仍校验，防爆破）
        if (CaptchaProperties.SCENE_LOGIN.equals(scene) && isCaptchaExemptLogin(request)) {
            return true;
        }

        // 读取请求头 token 并消费
        String token = request.getHeader(CaptchaProperties.HEADER_CAPTCHA_TOKEN);
        if (captchaTokenService.consume(token)) {
            return true;
        }

        // 校验失败：先 log，再以 data 包裹的失败 AjaxResult 阻断
        log.info("验证码校验拦截: scene={}, uri={}", scene, request.getRequestURI());
        ServletUtils.renderString(response, JSON.toJSONString(AjaxResult.error("请完成验证")));
        return false;
    }

    /**
     * 判断当前登录请求是否为「登录环节免行为验证码」的登录类型（短信 / 邮箱 / 微信扫码）。
     *
     * <p>短信、邮箱验证码登录的人机校验已在发码(sendCode)环节完成，且验证码一次性；微信扫码登录无法弹验证码。
     * 这几类登录在登录环节无需再次行为验证码，避免一次性 token 被发码消费后登录被误拦。仅在可重复读取的请求体上
     * 解析，避免消费原始流影响控制器入参；解析失败一律按需校验处理（从严）。</p>
     *
     * <p>接口信封加密开启时（{@code /auth/login} 在加密名单内），此处读到的请求体是 Base64 密文——
     * 正式解密发生在后置的 RequestBodyAdvice，晚于本拦截器。而 {@link ApiCryptoInterceptor}（order=-100）
     * 已先于本拦截器解出 AES 密钥并标记请求属性，因此这里按标记先行解密一份明文用于解析 loginType；
     * AES-GCM 解密无状态，不影响后续 Advice 对控制器入参的正式解密。</p>
     *
     * @param request 当前请求
     * @return true=短信/邮箱/微信登录（豁免行为验证码）
     */
    private boolean isCaptchaExemptLogin(HttpServletRequest request) {
        // 非可重复读取的请求体不解析，防止消费原始输入流
        if (!(request instanceof RepeatedlyRequestWrapper)) {
            return false;
        }
        try {
            String body = HttpHelper.getBodyString(request);
            // 请求体为空按需校验
            if (StrUtil.isBlank(body)) {
                return false;
            }
            // 接口加密请求：先解密出明文 JSON，再解析登录类型
            body = decryptIfNecessary(request, body);
            if (StrUtil.isBlank(body)) {
                return false;
            }
            // 解析登录类型，短信/邮箱/微信登录豁免
            String loginType = JSON.parseObject(body).getString("loginType");
            return CaptchaProperties.LOGIN_TYPE_SMS.equalsIgnoreCase(loginType)
                    || CaptchaProperties.LOGIN_TYPE_EMAIL.equalsIgnoreCase(loginType)
                    || CaptchaProperties.LOGIN_TYPE_WECHAT.equalsIgnoreCase(loginType);
        } catch (Exception e) {
            // 解析异常留痕并按需校验（从严）
            log.warn("登录请求体解析失败,按需行为验证码处理: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 若本请求被加密拦截器标记为「需解密」，则用本次请求的 AES 密钥 + IV 头解出明文 JSON；
     * 未标记（明文请求）原样返回。
     *
     * @param request 当前请求
     * @param body    原始请求体（明文 JSON 或 Base64 密文）
     * @return 明文 JSON 字符串
     */
    private String decryptIfNecessary(HttpServletRequest request, String body) {
        // 未标记需解密：明文请求，原样返回
        if (!Boolean.TRUE.equals(request.getAttribute(ApiCryptoInterceptor.ATTR_DECRYPT))) {
            return body;
        }
        byte[] aesKey = CryptoKeyHolder.getAesKey();
        // 防御性兜底：标记了解密但密钥缺失（理论上加密拦截器已保证），按解析失败从严处理
        if (aesKey == null) {
            log.warn("登录豁免判定: 请求已标记解密但缺少AES密钥, uri={}", request.getRequestURI());
            return null;
        }
        // 用与正式解密一致的协议（AES-GCM + IV 头）解出明文
        String iv = request.getHeader(ApiCryptoInterceptor.HEADER_IV);
        byte[] plain = envelopeCryptoTemplate.decryptRequestBody(body.trim(), aesKey, iv);
        return new String(plain, StandardCharsets.UTF_8);
    }
}
