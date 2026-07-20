package com.aid.auth.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aid.auth.domain.dto.BindAccountRequest;
import com.aid.auth.domain.dto.CancelAccountRequest;
import com.aid.auth.domain.dto.LoginRequest;
import com.aid.auth.domain.dto.ResetPasswordRequest;
import com.aid.auth.domain.dto.SendCodeRequest;
import com.aid.auth.domain.dto.UnbindAccountRequest;
import com.aid.auth.domain.vo.PublicConfigVO;
import com.aid.auth.policy.AuthCodePolicy;
import com.aid.auth.policy.AuthCodePolicyService;
import com.aid.aid.domain.vo.LoginVO;
import com.aid.aid.domain.vo.UserInfoVO;
import com.aid.aid.domain.vo.UserSocialVO;
import com.aid.auth.strategy.LoginStrategy;
import com.aid.aid.domain.AidUserProfile;
import com.aid.aid.domain.AidUserSocial;
import com.aid.aid.mapper.AidUserProfileMapper;
import com.aid.aid.mapper.AidUserSocialMapper;
import com.aid.common.aid.mail.utils.MailUtils;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.vo.OssUploadLimitsVO;
import com.aid.common.aid.sms.utils.SmsUtils;
import com.aid.common.constant.AuthConstants;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.aid.common.utils.ip.IpUtils;
import com.aid.common.utils.uuid.IdUtils;
import com.aid.common.core.service.TokenService;
import com.aid.core.service.ISysUserService;
import com.aid.notify.wechat.service.IWechatNotifyConfigService;
import com.aid.notify.wechat.vo.WechatNotifyPublicVO;
import com.aid.voice.service.VoicePreviewLimitService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 统一认证服务
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AuthService {

    @Resource
    private List<LoginStrategy> loginStrategies;

    @Resource
    private RedisCache redisCache;

    @Resource
    private TokenService tokenService;

    @Resource
    private ISysUserService userService;

    @Resource
    private AidUserProfileMapper userProfileMapper;

    @Resource
    private AidUserSocialMapper userSocialMapper;

    /** 验证码策略读取器：长度 / 有效期 / 间隔 / 日上限均由 aid_config 动态读取 */
    @Resource
    private AuthCodePolicyService authCodePolicyService;

    /** 多端在线策略执行器：按 aid_config(login_policy) 裁剪同账号会话 */
    @Resource
    private com.aid.auth.policy.OnlineSessionPolicy onlineSessionPolicy;

    /** 行为验证码服务：聚合到 publicConfig 接口供前端首屏一次性拉取 */
    @Resource
    private com.aid.common.captcha.service.CaptchaService captchaService;

    /** 接口加密配置提供者：聚合到 publicConfig，告知前端是否开启加密及公钥（惰性，缺失不影响其它配置） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.aid.common.aid.crypto.core.ApiCryptoConfigProvider> apiCryptoConfigProvider;

    /** 通用配置服务：读取 aid_config(category=basic) 的基础配置，聚合到 publicConfig 下发给 C 端 */
    @Resource
    private com.aid.aid.service.IAidConfigService aidConfigService;

    /** 文件存储模板：读取上传大小限制，聚合到 publicConfig 供 C 端上传前按类型提示/校验大小 */
    @Resource
    private OssTemplate ossTemplate;

    /** 配音试听限制服务：向前端下发当前秒数配置及预估最大字数 */
    @Resource
    private VoicePreviewLimitService voicePreviewLimitService;

    /** 支付宝配置管理器：读取支付宝开关，聚合到 publicConfig 告知 C 端支付宝是否可用 */
    @Resource
    private com.aid.common.aid.alipay.config.AlipayConfigManager alipayConfigManager;

    /** 微信支付配置管理器：读取微信支付开关，聚合到 publicConfig 告知 C 端微信支付是否可用 */
    @Resource
    private com.aid.common.aid.wxpay.config.WxpayConfigManager wxpayConfigManager;

    /** 微信公众号模板消息推送配置，聚合到 publicConfig 返回给 C 端 */
    @Resource
    private IWechatNotifyConfigService wechatNotifyConfigService;

    /** 基础配置分类标识（aid_config.category）。 */
    private static final String BASIC_CONFIG_CATEGORY = "basic";

    /**
     * 登录策略映射表
     */
    private final Map<String, LoginStrategy> strategyMap = new HashMap<>();

    /**
     * 初始化登录策略映射
     */
    @PostConstruct
    public void init() {
        if (CollUtil.isNotEmpty(loginStrategies)) {
            for (LoginStrategy strategy : loginStrategies) {
                strategyMap.put(strategy.getLoginType(), strategy);
            }
            log.info("登录策略初始化完成: {}", strategyMap.keySet());
        }
    }

    /**
     * 统一登录入口
     *
     * @param request 登录请求
     * @return 登录响应
     */
    public LoginVO login(LoginRequest request) {
        LoginStrategy strategy = strategyMap.get(request.getLoginType());
        if (Objects.isNull(strategy)) {
            throw new ServiceException("不支持的登录方式");
        }

        strategy.validate(request);

        LoginUser loginUser = strategy.login(request);

        String token = tokenService.createToken(loginUser);

        // 执行多端在线策略（关闭多端时挤下旧会话 / 限制最大在线数）
        onlineSessionPolicy.enforce(loginUser.getUserId(), loginUser.getToken());

        return buildLoginVO(loginUser, token);
    }

    /**
     * 退出登录
     */
    public void logout() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (Objects.nonNull(loginUser)) {
            String userName = loginUser.getUsername();
            tokenService.delLoginUser(loginUser.getToken());
            log.info("用户退出登录: userName={}", userName);
        }
    }

    /**
     * 发送验证码
     *
     * @param request 发送验证码请求
     */
    public void sendCode(SendCodeRequest request) {
        // codeType 必须显式传入，不允许兜底为 sms
        if (StrUtil.isBlank(request.getCodeType())) {
            throw new ServiceException("验证码类型错误");
        }
        String target = request.getTarget();
        String codeType = AuthConstants.normalizeChannel(request.getCodeType());
        String scene = request.getScene();

        // codeType 必须是 sms 或 email，早判断早抛短文案，避免下游 NPE
        if (!AuthConstants.BIND_TYPE_SMS.equals(codeType) && !AuthConstants.BIND_TYPE_EMAIL.equals(codeType)) {
            log.info("发送验证码渠道非法: codeType={}", request.getCodeType());
            throw new ServiceException("验证码类型错误");
        }
        // scene 必须是受支持枚举之一，防止 cache key 误用
        if (!AuthConstants.SCENE_LOGIN.equals(scene)
                && !AuthConstants.SCENE_BIND.equals(scene)
                && !AuthConstants.SCENE_UNBIND.equals(scene)
                && !AuthConstants.SCENE_RESET.equals(scene)
                && !AuthConstants.SCENE_CANCEL.equals(scene)) {
            log.info("发送验证码场景非法: scene={}", scene);
            throw new ServiceException("场景数据异常");
        }

        // 解绑 / 注销场景：从当前用户获取手机号/邮箱（target 客户端不传）
        if (AuthConstants.SCENE_UNBIND.equals(scene) || AuthConstants.SCENE_CANCEL.equals(scene)) {
            Long userId = SecurityUtils.getUserId();
            SysUser user = userService.selectUserById(userId);
            if (Objects.isNull(user)) {
                throw new ServiceException("用户不存在");
            }
            if (AuthConstants.BIND_TYPE_SMS.equals(codeType)) {
                target = user.getPhonenumber();
                if (StrUtil.isBlank(target)) {
                    throw new ServiceException("您未绑定手机号");
                }
            } else {
                target = user.getEmail();
                if (StrUtil.isBlank(target)) {
                    throw new ServiceException("您未绑定邮箱");
                }
            }
        }

        // 除解绑/注销外，其它场景 target 必填，防止 NPE
        if (StrUtil.isBlank(target)) {
            log.info("发送验证码 target 为空: scene={}, codeType={}", scene, codeType);
            throw new ServiceException("目标地址不能为空");
        }

        String clientIp = IpUtils.getIpAddr();

        validateTargetFormat(target, codeType);

        // 绑定场景：检查是否已被绑定
        if (AuthConstants.SCENE_BIND.equals(scene)) {
            checkBindTarget(target, codeType);
        }

        // 加载验证码策略：长度 / 有效期 / 间隔 / 日上限 全部从 aid_config 动态读取
        AuthCodePolicy policy = authCodePolicyService.getPolicy(codeType);

        // 一次性原子获取所有发送资格（间隔锁 SETNX + 日计数 INCR）
        // 任一资源被占用 / 超限 / Redis 异常 → 立即抛异常，已获取的资源在内部回滚
        RateLimitContext ctx = acquireRateLimits(target, codeType, clientIp, policy);

        // 生成验证码（长度按策略，policy 已对越界值兜底）
        String code = IdUtils.getRandomNum(policy.getCodeLength());

        // 发送 + 缓存验证码：任一步失败 → 回滚日计数（间隔锁保留：失败也算占用窗口，防短信轰炸）
        try {
            if (AuthConstants.BIND_TYPE_SMS.equals(codeType)) {
                sendSmsCode(target, code, scene);
            } else {
                sendEmailCode(target, code, scene, policy);
            }

            // 缓存验证码：TTL 来自策略，cache key 按 scene 隔离，避免跨场景复用
            String cacheKey = AuthConstants.getCodeCacheKey(scene, codeType, target);
            redisCache.setCacheObject(cacheKey, code, policy.getCodeExpireMinutes(), TimeUnit.MINUTES);
        } catch (RuntimeException e) {
            rollbackOnSendFail(ctx);
            throw e;
        }

        log.info("验证码发送成功: target={}, codeType={}, scene={}, ip={}", target, codeType, scene, clientIp);
    }

    /** publicConfig Redis 缓存 key（全局共享，不区分用户/IP）。 */
    private static final String PUBLIC_CONFIG_CACHE_KEY = "auth:public_config:v1";

    /** publicConfig 缓存 TTL（秒）：30s 是 aid_config 改完到全局生效的最大延迟，业务可接受。 */
    private static final int PUBLIC_CONFIG_CACHE_TTL_SECONDS = 30;

    /**
     * 一次性返回前端首屏所需的全部公开配置（行为验证码状态、短信/邮箱验证码策略、加密/支付/上传等），
     * 减少首屏多次匿名请求的往返；服务端 Redis 缓存 {@value #PUBLIC_CONFIG_CACHE_TTL_SECONDS}s，
     * aid_config / 行为验证码状态变更后最多 30s 内生效。
     * 缓存内容为不含密钥/会话的纯公开配置，跨用户共享读，无隐私泄露风险。
     */
    public PublicConfigVO getPublicConfig() {
        try {
            PublicConfigVO cached = redisCache.getCacheObject(PUBLIC_CONFIG_CACHE_KEY);
            if (cached != null) {
                return cached;
            }
        } catch (Exception e) {
            // 缓存读异常不阻塞主流程，继续走 build
            log.warn("publicConfig 缓存读取失败，回源构建: err={}", e.getMessage());
        }

        boolean enabled;
        com.aid.common.captcha.service.CaptchaService.Diagnostics diag;
        String currentType = null;
        try {
            enabled = captchaService.isOperational(false);
            diag = captchaService.diagnose();
            if (enabled) {
                currentType = captchaService.currentType();
            }
        } catch (Exception e) {
            log.error("publicConfig 行为验证码状态解析失败，按未开启降级", e);
            enabled = false;
            diag = new com.aid.common.captcha.service.CaptchaService.Diagnostics();
        }
        PublicConfigVO.CaptchaStatus captcha =
                PublicConfigVO.CaptchaStatus.builder()
                        .enabled(enabled)
                        .type(currentType)
                        .reason(diag.getReason())
                        .urlCount(diag.getBackgroundUrlCount())
                        .localCount(diag.getLocalImageCount())
                        .applicationOk(diag.isApplicationOk())
                        .imagesReady(diag.isImagesReady())
                        .build();

        AuthCodePolicy smsRaw = authCodePolicyService.getPolicy(AuthConstants.BIND_TYPE_SMS);
        AuthCodePolicy mailRaw = authCodePolicyService.getPolicy(AuthConstants.BIND_TYPE_EMAIL);

        PublicConfigVO.CryptoStatus crypto = buildCryptoStatus();

        Map<String, String> basic = buildBasicConfig();

        PublicConfigVO.PaymentChannels payment = buildPaymentChannels();

        WechatNotifyPublicVO wechatNotify = buildWechatNotifyStatus();

        OssUploadLimitsVO upload = buildUploadLimits();

        VoicePreviewLimitService.Limit voicePreviewLimit = voicePreviewLimitService.getLimit();
        PublicConfigVO.VoicePreviewConfig voicePreview = PublicConfigVO.VoicePreviewConfig.builder()
                .maxSeconds(voicePreviewLimit.maxSeconds())
                // 该值同时用于前端 maxLength 与后端校验，避免前后端按秒数各自估算
                .estimatedMaxChars(voicePreviewLimit.estimatedMaxChars())
                .build();

        PublicConfigVO vo = PublicConfigVO.builder()
                .captcha(captcha)
                .smsPolicy(toCodePolicyVo(smsRaw))
                .emailPolicy(toCodePolicyVo(mailRaw))
                .crypto(crypto)
                .wechatNotify(wechatNotify)
                .basic(basic)
                .payment(payment)
                .upload(upload)
                .voicePreview(voicePreview)
                .serverTime(System.currentTimeMillis())
                .build();

        try {
            redisCache.setCacheObject(PUBLIC_CONFIG_CACHE_KEY, vo,
                    PUBLIC_CONFIG_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("publicConfig 缓存写入失败（不影响响应）: err={}", e.getMessage());
        }
        return vo;
    }

    private PublicConfigVO.CodePolicy toCodePolicyVo(AuthCodePolicy raw) {
        if (raw == null) {
            return null;
        }
        return PublicConfigVO.CodePolicy.builder()
                .channel(raw.getChannel())
                .codeLength(raw.getCodeLength())
                .codeExpireMinutes(raw.getCodeExpireMinutes())
                .sendIntervalSeconds(raw.getSendIntervalSeconds())
                .dailyLimit(raw.getDailyLimit())
                .build();
    }

    /**
     * 构建接口加密状态块。
     * 从 aid_config(category=api_crypto) 读取开关与公钥：开启时下发公钥（前端用于加密一次性 AES 密钥），
     * 关闭 / 未配置 / 读取异常时一律按“未开启”降级，绝不阻塞首屏配置返回，也绝不下发私钥。
     */
    private PublicConfigVO.CryptoStatus buildCryptoStatus() {
        try {
            com.aid.common.aid.crypto.core.ApiCryptoConfigProvider provider =
                    apiCryptoConfigProvider.getIfAvailable();
            if (Objects.isNull(provider)) {
                // 提供者未装配：按未开启返回
                return PublicConfigVO.CryptoStatus.builder()
                        .enabled(false).build();
            }
            com.aid.common.aid.crypto.config.ApiCryptoConfig cfg = provider.getConfig();
            boolean enabled = cfg.isEnabled() && StrUtil.isNotBlank(cfg.getPublicKey());
            return PublicConfigVO.CryptoStatus.builder()
                    .enabled(enabled)
                    // 仅开启时下发公钥；私钥永不外泄
                    .publicKey(enabled ? cfg.getPublicKey() : null)
                    .algorithm(enabled ? "RSA-OAEP-SHA256+AES-GCM-256" : null)
                    .build();
        } catch (Exception e) {
            // 任何异常都不影响其它公开配置返回
            log.error("publicConfig 接口加密状态解析失败，按未开启降级", e);
            return PublicConfigVO.CryptoStatus.builder()
                    .enabled(false).build();
        }
    }

    /**
     * 构建基础配置块。
     * 读取 aid_config(category=basic) 下的全部键值对（协议/隐私政策/版本号/备案号/交流二维码等合规与首屏展示内容），
     * 以 configName → configValue 形式动态下发。后台 aid_config 新增同分类配置项即自动随接口生效，无需改代码。
     * 读取异常或无配置时返回空 Map，绝不阻塞其它公开配置返回。
     *
     * @return 基础配置键值对（不会为 null）
     */
    private Map<String, String> buildBasicConfig() {
        try {
            com.aid.aid.domain.AidConfig query = new com.aid.aid.domain.AidConfig();
            query.setCategory(BASIC_CONFIG_CATEGORY);
            List<com.aid.aid.domain.AidConfig> list = aidConfigService.selectAidConfigList(query);
            if (CollUtil.isEmpty(list)) {
                return java.util.Collections.emptyMap();
            }
            Map<String, String> basic = new java.util.LinkedHashMap<>(list.size());
            for (com.aid.aid.domain.AidConfig item : list) {
                if (item != null && StrUtil.isNotBlank(item.getConfigName())) {
                    basic.put(item.getConfigName(), item.getConfigValue());
                }
            }
            return basic;
        } catch (Exception e) {
            // 基础配置读取失败不影响其它公开配置返回
            log.error("publicConfig 基础配置(category=basic)读取失败，按空配置降级", e);
            return java.util.Collections.emptyMap();
        }
    }

    /**
     * 构建上传大小限制块。
     * 读取当前生效的分类型上传限制（各资源类型单文件上限 + 允许扩展名）与全局兜底，供 C 端上传前按类型提示/校验大小，
     * 与后台「文件存储 → 上传大小限制」配置保持一致。读取异常时返回空对象，绝不阻塞其它公开配置返回。
     *
     * @return 上传大小限制（不会为 null）
     */
    private OssUploadLimitsVO buildUploadLimits() {
        try {
            OssUploadLimitsVO limits = ossTemplate.getUploadLimits();
            return Objects.isNull(limits) ? new OssUploadLimitsVO() : limits;
        } catch (Exception e) {
            // 上传限制读取失败不影响其它公开配置返回
            log.error("publicConfig 上传大小限制读取失败，按空配置降级", e);
            return new OssUploadLimitsVO();
        }
    }

    /**
     * 构建支付渠道开关块。
     * 读取支付宝 / 微信支付的启用开关（{@code AlipayConfigManager#isEnabled} /
     * {@code WxpayConfigManager#isEnabled}，即后台"同步配置"后生效的内存口径，与实际下单的可用性一致），
     * 告知 C 端收银台应展示哪些支付方式。任一读取异常都按"未开启"降级，绝不阻塞其它公开配置返回。
     *
     * @return 支付渠道开关（不会为 null）
     */
    private PublicConfigVO.PaymentChannels buildPaymentChannels() {
        boolean alipayEnabled = false;
        boolean wxpayEnabled = false;
        try {
            alipayEnabled = alipayConfigManager.isEnabled();
        } catch (Exception e) {
            log.error("publicConfig 支付宝开关读取失败，按未开启降级", e);
        }
        try {
            wxpayEnabled = wxpayConfigManager.isEnabled();
        } catch (Exception e) {
            log.error("publicConfig 微信支付开关读取失败，按未开启降级", e);
        }
        return PublicConfigVO.PaymentChannels.builder()
                .alipayEnabled(alipayEnabled)
                .wxpayEnabled(wxpayEnabled)
                .build();
    }

    private WechatNotifyPublicVO buildWechatNotifyStatus() {
        try {
            return wechatNotifyConfigService.getPublicStatus();
        } catch (Exception e) {
            log.error("publicConfig 微信推送配置读取失败，按关闭降级", e);
            return WechatNotifyPublicVO.builder()
                    .enabled(false)
                    .rules(java.util.Collections.emptyList())
                    .build();
        }
    }

    /**
     * 绑定账号。
     *
     * @param request 绑定请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void bindAccount(BindAccountRequest request) {
        Long userId = SecurityUtils.getUserId();
        SysUser user = userService.selectUserById(userId);
        if (Objects.isNull(user)) {
            throw new ServiceException("用户不存在");
        }

        // 兼容 phone 别名，统一映射到内部枚举 sms
        String bindType = AuthConstants.normalizeChannel(request.getBindType());
        String target = request.getTarget();
        String code = request.getCode();

        // 明确禁止 wechat / 其它分支
        if (AuthConstants.BIND_TYPE_WECHAT.equals(bindType)) {
            log.warn("/auth/bind 不再支持 wechat 直接绑定, userId={}", userId);
            throw new ServiceException("请扫码绑定微信");
        }
        if (!AuthConstants.BIND_TYPE_SMS.equals(bindType) && !AuthConstants.BIND_TYPE_EMAIL.equals(bindType)) {
            throw new ServiceException("绑定类型错误");
        }

        // 手机号/邮箱绑定都需要 target + 验证码
        if (StrUtil.isBlank(target)) {
            throw new ServiceException("目标地址不能为空");
        }
        if (StrUtil.isBlank(code)) {
            throw new ServiceException("验证码不能为空");
        }
        validateTargetFormat(target, bindType);
        validateCode(AuthConstants.SCENE_BIND, target, bindType, code);

        // 根据绑定类型执行绑定（不会触发 role/post 清空）
        if (AuthConstants.BIND_TYPE_SMS.equals(bindType)) {
            bindPhone(user, target);
        } else {
            bindEmail(user, target);
        }

        log.info("账号绑定成功: userId={}, bindType={}", userId, bindType);
    }

    /**
     * 找回密码/重置密码
     *
     * 使用 {@link ISysUserService#resetUserPwd(Long, String)} 单字段更新，不清空用户角色 / 岗位；
     * 验证码 cache key 使用 {@code scene=reset} 隔离，登录验证码不能被复用于重置。
     *
     * @param request 重置密码请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(ResetPasswordRequest request) {
        String target = request.getTarget();
        // 兼容 phone / mail 别名
        String resetType = AuthConstants.normalizeChannel(request.getResetType());
        String code = request.getCode();
        String newPassword = request.getNewPassword();
        String confirmPassword = request.getConfirmPassword();

        if (!AuthConstants.BIND_TYPE_SMS.equals(resetType) && !AuthConstants.BIND_TYPE_EMAIL.equals(resetType)) {
            throw new ServiceException("重置方式错误");
        }

        if (!Objects.equals(newPassword, confirmPassword)) {
            throw new ServiceException("两次密码不一致");
        }

        // 校验验证码（按 reset 场景隔离）
        validateCode(AuthConstants.SCENE_RESET, target, resetType, code);

        SysUser user = findUserByTarget(target, resetType);
        if (Objects.isNull(user)) {
            throw new ServiceException("账号不存在");
        }

        // 直接重置密码字段，不触发角色/岗位清空逻辑
        userService.resetUserPwd(user.getUserId(), SecurityUtils.encryptPassword(newPassword));

        log.info("密码重置成功: userId={}", user.getUserId());
    }

    /**
     * 注销账号。要求二次验证码确认：用户须先通过绑定的手机号或邮箱收到验证码再调本接口，
     * 防止会话被劫持后被一键注销。
     *
     * 验证码 cache key 使用 {@code scene=cancel} 隔离；敏感信息脱敏使用
     * {@link ISysUserService#updateUserProfile(SysUser)}，避免清空角色 / 岗位。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelAccount(CancelAccountRequest request) {
        Long userId = SecurityUtils.getUserId();
        SysUser user = userService.selectUserById(userId);
        if (Objects.isNull(user)) {
            throw new ServiceException("用户不存在");
        }

        // 二次验证码校验，verifyType 兼容 phone / mail 别名
        String verifyType = request == null ? null : AuthConstants.normalizeChannel(request.getVerifyType());
        String code = request == null ? null : request.getCode();
        if (StrUtil.isBlank(verifyType) || StrUtil.isBlank(code)) {
            throw new ServiceException("验证码不能为空");
        }
        String target;
        if (AuthConstants.BIND_TYPE_SMS.equals(verifyType)) {
            target = user.getPhonenumber();
            if (StrUtil.isBlank(target)) {
                throw new ServiceException("您未绑定手机号");
            }
        } else if (AuthConstants.BIND_TYPE_EMAIL.equals(verifyType)) {
            target = user.getEmail();
            if (StrUtil.isBlank(target)) {
                throw new ServiceException("您未绑定邮箱");
            }
        } else {
            throw new ServiceException("验证方式错误");
        }
        // 使用 cancel 场景独立 cache key
        validateCode(AuthConstants.SCENE_CANCEL, target, verifyType, code);

        // 特殊化处理用户敏感信息，防止精确查询时出问题；
        // 拼接前按列长（均为 varchar(100)）截断原值预留后缀空间，防超长导致注销 UPDATE 失败
        String suffix = "_cancelled_" + userId + "_" + System.currentTimeMillis();
        SysUser updateUser = new SysUser();
        updateUser.setUserId(userId);
        updateUser.setUserName(truncateForSuffix(user.getUserName(), 100, suffix));
        if (Objects.nonNull(user.getPhonenumber())) {
            updateUser.setPhonenumber(truncateForSuffix(user.getPhonenumber(), 100, suffix));
        }
        if (Objects.nonNull(user.getEmail())) {
            updateUser.setEmail(truncateForSuffix(user.getEmail(), 100, suffix));
        }
        // 不走 updateUser，避免清空角色/岗位关联表
        userService.updateUserProfile(updateUser);

        // 逻辑删除用户扩展信息
        LambdaQueryWrapper<AidUserProfile> profileWrapper = new LambdaQueryWrapper<>();
        profileWrapper.eq(AidUserProfile::getUserId, userId)
                .eq(AidUserProfile::getDelFlag, "0");
        AidUserProfile profile = userProfileMapper.selectOne(profileWrapper);
        if (Objects.nonNull(profile)) {
            AidUserProfile updateProfile = new AidUserProfile();
            updateProfile.setId(profile.getId());
            updateProfile.setDelFlag("2");
            userProfileMapper.updateById(updateProfile);
        }

        // 硬删除用户第三方登录绑定关系
        LambdaQueryWrapper<AidUserSocial> socialWrapper = new LambdaQueryWrapper<>();
        socialWrapper.eq(AidUserSocial::getUserId, userId);
        int deletedCount = userSocialMapper.delete(socialWrapper);
        log.info("注销账号-删除第三方登录绑定: userId={}, deletedCount={}", userId, deletedCount);

        // 先 logout 清掉当前会话 token，再 deleteUserById 逻辑删除用户：
        //   若先删除后登出，logout() 内的 SecurityUtils.getLoginUser() 可能取不到用户导致 token 清理失败，
        //   事务回滚时还会残留悬挂会话。
        try {
            logout();
        } catch (Exception logoutEx) {
            // logout 失败不影响注销主流程（事务即将结束，token 清理会在 afterCompletion 补偿），
            // 但记录 WARN 便于排查
            log.warn("账号注销时 logout 失败, userId={}, err={}", userId, logoutEx.getMessage());
        }

        userService.deleteUserById(userId);

        log.info("账号注销成功: userId={}", userId);
    }

    /**
     * 构建登录响应
     *
     * 手机号 / 真实姓名 / 身份证在出参做脱敏，避免 PII 直接落到客户端；邮箱保留前 1 位 + @ 后缀，余下打星号。
     */
    private LoginVO buildLoginVO(LoginUser loginUser, String token) {
        SysUser user = loginUser.getUser();
        Long userId = user.getUserId();

        List<UserSocialVO> socialList = getUserSocialList(userId);

        return LoginVO.builder()
                .token(token)
                // 复用统一的用户信息组装逻辑，保证登录与个人信息接口出参一致
                .userInfo(buildUserInfoVO(user))
                .social(socialList)
                .build();
    }

    /**
     * 查询当前登录用户的个人信息。
     *
     * @return 用户信息（脱敏后）
     */
    public UserInfoVO getCurrentUserInfo() {
        Long userId = SecurityUtils.getUserId();
        // 余额等易变字段实时查库，避免前端沿用登录时的旧值
        SysUser user = userService.selectUserById(userId);
        if (Objects.isNull(user)) {
            log.error("查询个人信息失败：用户不存在, userId={}", userId);
            throw new ServiceException("用户不存在");
        }
        return buildUserInfoVO(user);
    }

    /**
     * 组装用户信息出参（PII 脱敏）
     *
     * 登录与个人信息接口共用，确保两处出参字段及脱敏规则一致。
     *
     * @param user 用户主信息
     * @return 用户信息 VO
     */
    private UserInfoVO buildUserInfoVO(SysUser user) {
        Long userId = user.getUserId();

        AidUserProfile profile = getUserProfile(userId);

        // 构建用户信息（PII 脱敏）
        UserInfoVO.UserInfoVOBuilder userInfoBuilder = UserInfoVO.builder()
                .userId(userId)
                .userName(user.getUserName())
                .nickName(user.getNickName())
                .avatar(user.getAvatar())
                .phonenumber(maskPhone(user.getPhonenumber()))
                .email(maskEmail(user.getEmail()));

        if (profile != null) {
            userInfoBuilder
                    .balance(profile.getBalance())
                    .frozenBalance(profile.getFrozenBalance())
                    .memberLevel(profile.getMemberLevel())
                    .memberExpireTime(profile.getMemberExpireTime())
                    .totalRecharge(profile.getTotalRecharge())
                    .totalConsumption(profile.getTotalConsumption())
                    .wechatNotifyEnabled(Integer.valueOf(1).equals(profile.getWechatNotifyEnabled()))
                    // 实名认证信息（脱敏）
                    .isReal("1".equals(profile.getIsReal()))
                    .realName(maskRealName(profile.getRealName()))
                    .idCard(maskIdCard(profile.getIdCard()));
        }

        return userInfoBuilder.build();
    }

    /**
     * 手机号脱敏：保留前 3 位 + 后 4 位，中间 4 位星号；非 11 位手机号不处理。
     */
    private String maskPhone(String phone) {
        if (StrUtil.isBlank(phone) || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /**
     * 邮箱脱敏：保留前 1 位 + @后缀。
     */
    private String maskEmail(String email) {
        if (StrUtil.isBlank(email)) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return email;
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /**
     * 真实姓名脱敏：保留首字符，其余替换为 *。
     */
    private String maskRealName(String name) {
        if (StrUtil.isBlank(name)) {
            return name;
        }
        if (name.length() == 1) {
            return name;
        }
        StringBuilder sb = new StringBuilder().append(name.charAt(0));
        for (int i = 1; i < name.length(); i++) {
            sb.append('*');
        }
        return sb.toString();
    }

    /**
     * 身份证脱敏：保留前 3 + 后 4，中间打星号。
     */
    private String maskIdCard(String idCard) {
        if (StrUtil.isBlank(idCard) || idCard.length() < 8) {
            return idCard;
        }
        int len = idCard.length();
        return idCard.substring(0, 3) + "***********" + idCard.substring(len - 4);
    }

    /**
     * 获取用户扩展信息
     */
    private AidUserProfile getUserProfile(Long userId) {
        LambdaQueryWrapper<AidUserProfile> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AidUserProfile::getUserId, userId)
                .eq(AidUserProfile::getDelFlag, "0");
        return userProfileMapper.selectOne(queryWrapper);
    }

    /**
     * 获取用户社交账号列表
     */
    private List<UserSocialVO> getUserSocialList(Long userId) {
        LambdaQueryWrapper<AidUserSocial> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AidUserSocial::getUserId, userId)
                .eq(AidUserSocial::getDelFlag, "0");
        List<AidUserSocial> socialList = userSocialMapper.selectList(queryWrapper);

        if (CollUtil.isEmpty(socialList)) {
            return new ArrayList<>();
        }

        return socialList.stream()
                .map(social -> UserSocialVO.builder()
                        .platformSource(social.getPlatformSource())
                        .openid(social.getOpenid())
                        .unionid(social.getUnionid())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 校验发送频率限制（间隔锁 + 每日上限）—— 抢锁式实现。
     *
     * @param target   手机号或邮箱
     * @param codeType 类型 sms/email
     * @param clientIp 客户端IP
     * @param policy   当前渠道的策略
     * @return 持有锁/计数信息的上下文，主流程后续在 sendCode 中决定提交（不释放）或回滚
     */
    private RateLimitContext acquireRateLimits(String target, String codeType, String clientIp, AuthCodePolicy policy) {
        int interval = policy.getSendIntervalSeconds();
        String day = todayKeySuffix();
        RateLimitContext ctx = new RateLimitContext();
        ctx.targetLockKey = "code_limit:" + codeType + ":target:" + target;
        ctx.ipLockKey = "code_limit:" + codeType + ":ip:" + clientIp;
        ctx.targetDailyKey = "code_daily:" + codeType + ":target:" + target + ":" + day;
        ctx.ipDailyKey = "code_daily:" + codeType + ":ip:" + clientIp + ":" + day;
        ctx.targetLockToken = java.util.UUID.randomUUID().toString();
        ctx.ipLockToken = java.util.UUID.randomUUID().toString();

        try {
            if (!tryAcquireIntervalLock(ctx.targetLockKey, ctx.targetLockToken, interval)) {
                long ttl = redisCache.getExpire(ctx.targetLockKey);
                log.info("验证码发送频率限制: target={}, codeType={}", target, codeType);
                throw new ServiceException("发送过于频繁，请" + Math.max(ttl, 1) + "秒后重试");
            }
            ctx.targetLockAcquired = true;

            if (!tryAcquireIntervalLock(ctx.ipLockKey, ctx.ipLockToken, interval)) {
                long ttl = redisCache.getExpire(ctx.ipLockKey);
                log.info("验证码发送IP频率限制: ip={}, codeType={}", clientIp, codeType);
                throw new ServiceException("发送过于频繁，请" + Math.max(ttl, 1) + "秒后重试");
            }
            ctx.ipLockAcquired = true;

            if (policy.getDailyLimit() <= 0) {
                return ctx;
            }

            long targetCount = atomicIncrementDailyCounter(ctx.targetDailyKey);
            ctx.targetDailyIncremented = true;
            if (targetCount > policy.getDailyLimit()) {
                log.info("验证码日上限拒绝: target={}, codeType={}, count={}, limit={}",
                        target, codeType, targetCount, policy.getDailyLimit());
                throw new ServiceException("今日发送次数已达上限");
            }

            long ipCount = atomicIncrementDailyCounter(ctx.ipDailyKey);
            ctx.ipDailyIncremented = true;
            if (ipCount > policy.getDailyLimit()) {
                log.info("验证码IP日上限拒绝: ip={}, codeType={}, count={}, limit={}",
                        clientIp, codeType, ipCount, policy.getDailyLimit());
                throw new ServiceException("今日发送次数已达上限");
            }

            return ctx;
        } catch (RuntimeException e) {
            // 任一步失败（业务超限 or Redis 异常）：补偿已获取的锁/计数
            releaseAcquiredResources(ctx);
            throw e;
        }
    }

    /**
     * 当 sendCode 发送/缓存验证码任一步失败时回滚日计数（间隔锁保留：失败也算占用窗口，防轰炸）。
     */
    private void rollbackOnSendFail(RateLimitContext ctx) {
        if (ctx == null) {
            return;
        }
        if (ctx.targetDailyIncremented) {
            atomicDecrement(ctx.targetDailyKey);
            ctx.targetDailyIncremented = false;
        }
        if (ctx.ipDailyIncremented) {
            atomicDecrement(ctx.ipDailyKey);
            ctx.ipDailyIncremented = false;
        }
    }

    /**
     * 发生异常时把已获取的资源全部还回去（间隔锁 + 日计数）。
     * 仅在 acquireRateLimits 内部超限/异常的"业务还没真正开始"路径使用。
     */
    private void releaseAcquiredResources(RateLimitContext ctx) {
        if (ctx == null) {
            return;
        }
        if (ctx.targetDailyIncremented) {
            atomicDecrement(ctx.targetDailyKey);
        }
        if (ctx.ipDailyIncremented) {
            atomicDecrement(ctx.ipDailyKey);
        }
        if (ctx.targetLockAcquired) {
            safeReleaseIntervalLock(ctx.targetLockKey, ctx.targetLockToken);
        }
        if (ctx.ipLockAcquired) {
            safeReleaseIntervalLock(ctx.ipLockKey, ctx.ipLockToken);
        }
    }

    /** 当天 yyyyMMdd 字符串，用于做日维度计数 key 后缀 */
    private String todayKeySuffix() {
        return new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
    }

    /**
     * 用 {@code SET key value NX EX timeout} 抢间隔锁（原子）
     *
     * @param key     Redis key
     * @param token   本次请求生成的 UUID，作为锁值；释放时校验防误删
     * @param seconds 锁 TTL 秒
     * @return true 表示抢到，false 表示已被占用
     */
    @SuppressWarnings("unchecked")
    private boolean tryAcquireIntervalLock(String key, String token, int seconds) {
        Boolean ok = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(key, token, seconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ok);
    }

    /** Lua 脚本：GET key 等于传入 token 才 DEL，单条原子，防误删 */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    /**
     * Lua 脚本：原子地"INCR 并在首次创建时一次性设置 TTL"。
     * 用单条 Redis 命令组完成，避免 INCR 成功而 EXPIRE 抛错导致出现没有 TTL 的日计数 key。
     *
     * TTL 直接硬编码到脚本里（36 小时 = 129600 秒）：项目 RedisTemplate 用 FastJson 序列化 value，
     * 若用 ARGV 传 TTL，字符串 "129600" 会被序列化为带引号的 JSON 字符串，导致 Lua 里
     * {@code EXPIRE KEYS[1] ARGV[1]} 报 "value is not an integer or out of range"，硬编码可避开该序列化问题。
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> INCR_WITH_TTL_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local v = redis.call('incr', KEYS[1])\n" +
                    "if v == 1 then redis.call('expire', KEYS[1], 129600) end\n" +
                    "return v",
                    Long.class);

    /**
     * 安全释放间隔锁：Lua 比对 token 一致才 DEL。
     * 锁已自然过期或被别的请求重新占用时，本调用一律不删别人的锁。
     */
    @SuppressWarnings("unchecked")
    private void safeReleaseIntervalLock(String key, String token) {
        try {
            redisCache.redisTemplate.execute(
                    RELEASE_LOCK_SCRIPT,
                    java.util.Collections.singletonList(key),
                    token);
        } catch (Exception e) {
            // 释放失败仅记日志：锁会按 TTL 自然过期，不影响业务
            log.warn("间隔锁释放失败: key={}, msg={}", key, e.getMessage());
        }
    }

    /**
     * 原子递增日计数。INCR + 首次 EXPIRE 用一条 Lua 脚本原子完成，
     * 保证不会出现"已 INCR 但没设 TTL"的脏键，第二日同 key 重新 INCR 会因为前一天已过期从 1 重新开始。
     * TTL 已硬编码到脚本里（129600 秒 = 36 小时），无需 ARGV。
     */
    @SuppressWarnings("unchecked")
    private long atomicIncrementDailyCounter(String key) {
        Object val = redisCache.redisTemplate.execute(
                INCR_WITH_TTL_SCRIPT,
                java.util.Collections.singletonList(key));
        return Objects.nonNull(val) ? ((Number) val).longValue() : 1L;
    }

    /** 原子回滚一次（超限时撤销 increment） */
    @SuppressWarnings("unchecked")
    private void atomicDecrement(String key) {
        try {
            redisCache.redisTemplate.opsForValue().decrement(key);
        } catch (Exception e) {
            // 回滚失败不影响主流程，只记日志
            log.warn("日计数回滚失败: key={}, msg={}", key, e.getMessage());
        }
    }

    /**
     * 限流上下文：记录本次请求获取的间隔锁 token / 是否已自增日计数，
     * 供失败时按需补偿。所有字段在 sendCode 主流程内串行访问，无需线程安全。
     */
    private static class RateLimitContext {
        String targetLockKey;
        String ipLockKey;
        String targetDailyKey;
        String ipDailyKey;
        String targetLockToken;
        String ipLockToken;
        boolean targetLockAcquired;
        boolean ipLockAcquired;
        boolean targetDailyIncremented;
        boolean ipDailyIncremented;
    }

    /**
     * 发送短信验证码
     *
     * @param phone 手机号
     * @param code  验证码
     * @param scene 业务场景
     */
    private void sendSmsCode(String phone, String code, String scene) {
        try {
            SmsUtils.sendCode(phone, code);
            // 验证码 code 不明文入日志
            log.info("短信验证码发送成功: phone={}, scene={}, codeLen={}", phone, scene, code == null ? 0 : code.length());
        } catch (Exception e) {
            log.error("短信验证码发送失败: phone={}, scene={}, codeLen={}", phone, scene, code == null ? 0 : code.length(), e);
            throw new ServiceException("短信发送失败");
        }
    }

    /**
     * 发送邮箱验证码
     *
     * @param email  邮箱
     * @param code   验证码
     * @param scene  业务场景
     * @param policy 验证码策略（提供有效期等动态参数，注入邮件正文）
     */
    private void sendEmailCode(String email, String code, String scene, AuthCodePolicy policy) {
        try {
            String subject = "验证码";
            String content = buildEmailContent(code, scene, policy);
            MailUtils.sendHtml(email, subject, content);
            log.info("邮箱验证码发送成功: email={}, scene={}", email, scene);
        } catch (Exception e) {
            log.error("邮箱验证码发送失败: email={}, scene={}", email, scene, e);
            throw new ServiceException("邮件发送失败");
        }
    }

    /**
     * 构建邮件内容。
     *
     * @param code   验证码（服务端生成）
     * @param scene  业务场景（服务端枚举）
     * @param policy 当前渠道策略（取 codeExpireMinutes 拼到提示文案）
     * @return 邮件内容 HTML
     */
    private String buildEmailContent(String code, String scene, AuthCodePolicy policy) {
        String sceneName;
        if (AuthConstants.SCENE_LOGIN.equals(scene)) {
            sceneName = "登录";
        } else if (AuthConstants.SCENE_BIND.equals(scene)) {
            sceneName = "绑定账号";
        } else if (AuthConstants.SCENE_RESET.equals(scene)) {
            sceneName = "重置密码";
        } else {
            sceneName = "验证";
        }

        // 对动态内容统一 HTML 转义（sceneName 已受控，code 同样走转义兜底）
        String safeSceneName = escapeHtml(sceneName);
        String safeCode = escapeHtml(code);

        return "<div style='padding: 20px; background-color: #f5f5f5;'>" +
                "<div style='max-width: 600px; margin: 0 auto; background-color: #fff; padding: 30px; border-radius: 10px;'>" +
                "<h2 style='color: #333; text-align: center;'>" + safeSceneName + "验证码</h2>" +
                "<p style='color: #666; font-size: 14px;'>您好！</p>" +
                "<p style='color: #666; font-size: 14px;'>您正在进行" + safeSceneName + "操作，验证码为：</p>" +
                "<div style='text-align: center; margin: 30px 0;'>" +
                "<span style='font-size: 32px; font-weight: bold; color: #007bff; letter-spacing: 5px;'>" + safeCode + "</span>" +
                "</div>" +
                "<p style='color: #999; font-size: 12px;'>验证码有效期为" + policy.getCodeExpireMinutes() + "分钟，请勿将验证码告知他人。</p>" +
                "<p style='color: #999; font-size: 12px;'>如非本人操作，请忽略此邮件。</p>" +
                "</div></div>";
    }

    /**
     * HTML 转义：防止 XSS 注入。保持实现轻量，避免引入额外依赖。
     */
    private String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#x27;");
                case '/' -> sb.append("&#x2F;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 校验验证码（带场景隔离）
     *
     * @param scene    业务场景：login / bind / unbind / reset / cancel
     * @param target   手机号或邮箱
     * @param codeType 渠道：sms / email
     * @param code     用户输入的验证码
     */
    private void validateCode(String scene, String target, String codeType, String code) {
        if (StrUtil.isBlank(code)) {
            throw new ServiceException("验证码不能为空");
        }
        String cacheKey = AuthConstants.getCodeCacheKey(scene, codeType, target);
        String cachedCode = redisCache.getCacheObject(cacheKey);

        if (Objects.isNull(cachedCode)) {
            log.info("验证码已过期: scene={}, codeType={}, target={}", scene, codeType, target);
            throw new ServiceException("验证码已过期");
        }
        if (!Objects.equals(cachedCode, code)) {
            log.info("验证码错误: scene={}, codeType={}, target={}", scene, codeType, target);
            throw new ServiceException("验证码错误");
        }

        // 验证通过后删除验证码
        redisCache.deleteObject(cacheKey);
    }

    /**
     * 绑定手机号
     *
     * 使用 {@link ISysUserService#updateUserProfile(SysUser)} 更新，不清空角色 / 岗位。
     */
    private void bindPhone(SysUser user, String phone) {
        // 检查手机号是否已被使用（防止并发）
        SysUser existUser = userService.selectUserByPhonenumber(phone);
        if (Objects.nonNull(existUser)) {
            if (Objects.equals(existUser.getUserId(), user.getUserId())) {
                throw new ServiceException("您已绑定该手机号，无需重复绑定");
            }
            throw new ServiceException("该手机号已被其他账号绑定");
        }

        SysUser updateUser = new SysUser();
        updateUser.setUserId(user.getUserId());
        updateUser.setPhonenumber(phone);
        // 更新者：当前登录用户
        updateUser.setUpdateBy(user.getUserName());
        try {
            userService.updateUserProfile(updateUser);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：并发绑定同一手机号时后到者被数据库拦截
            log.info("绑定手机号并发冲突, userId={}", user.getUserId());
            throw new ServiceException("该手机号已被其他账号绑定");
        }
    }

    /**
     * 绑定邮箱
     *
     * 使用 {@link ISysUserService#updateUserProfile(SysUser)} 更新，不清空角色 / 岗位。
     */
    private void bindEmail(SysUser user, String email) {
        // 检查邮箱是否已被使用（防止并发）
        SysUser existUser = userService.selectUserByEmail(email);
        if (Objects.nonNull(existUser)) {
            if (Objects.equals(existUser.getUserId(), user.getUserId())) {
                throw new ServiceException("您已绑定该邮箱，无需重复绑定");
            }
            throw new ServiceException("该邮箱已被其他账号绑定");
        }

        SysUser updateUser = new SysUser();
        updateUser.setUserId(user.getUserId());
        updateUser.setEmail(email);
        // 更新者：当前登录用户
        updateUser.setUpdateBy(user.getUserName());
        try {
            userService.updateUserProfile(updateUser);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：并发绑定同一邮箱时后到者被数据库拦截
            log.info("绑定邮箱并发冲突, userId={}", user.getUserId());
            throw new ServiceException("该邮箱已被其他账号绑定");
        }
    }

    /**
     * 根据目标查找用户
     */
    private SysUser findUserByTarget(String target, String resetType) {
        if (AuthConstants.BIND_TYPE_SMS.equals(resetType)) {
            return userService.selectUserByPhonenumber(target);
        } else if (AuthConstants.BIND_TYPE_EMAIL.equals(resetType)) {
            return userService.selectUserByEmail(target);
        }
        return null;
    }

    /**
     * 校验手机号/邮箱格式
     *
     * @param target   目标地址
     * @param codeType 类型 phone/email
     */
    private void validateTargetFormat(String target, String codeType) {
        if (AuthConstants.BIND_TYPE_SMS.equals(codeType)) {
            // 仅支持中国大陆手机号
            if (!target.matches("^1[3-9]\\d{9}$")) {
                throw new ServiceException("手机号格式不正确");
            }
        } else if (AuthConstants.BIND_TYPE_EMAIL.equals(codeType)) {
            if (!target.matches("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")) {
                throw new ServiceException("邮箱格式不正确");
            }
        }
    }

    /**
     * 检查绑定目标是否已被使用
     *
     * @param target   目标地址
     * @param codeType 类型 phone/email
     */
    private void checkBindTarget(String target, String codeType) {
        Long currentUserId = SecurityUtils.getUserId();

        if (AuthConstants.BIND_TYPE_SMS.equals(codeType)) {
            SysUser existUser = userService.selectUserByPhonenumber(target);
            if (Objects.nonNull(existUser)) {
                if (Objects.equals(existUser.getUserId(), currentUserId)) {
                    throw new ServiceException("您已绑定该手机号");
                }
                throw new ServiceException("该手机号已被其他账号绑定");
            }
        } else if (AuthConstants.BIND_TYPE_EMAIL.equals(codeType)) {
            SysUser existUser = userService.selectUserByEmail(target);
            if (Objects.nonNull(existUser)) {
                if (Objects.equals(existUser.getUserId(), currentUserId)) {
                    throw new ServiceException("您已绑定该邮箱");
                }
                throw new ServiceException("该邮箱已被其他账号绑定");
            }
        }
    }

    /**
     * 解绑账号
     *
     * 验证码 cache key 使用 {@code scene=unbind} 隔离；{@code unbindType} 兼容 phone / mail 别名。
     *
     * @param request 解绑请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void unbindAccount(UnbindAccountRequest request) {
        Long userId = SecurityUtils.getUserId();
        SysUser user = userService.selectUserById(userId);
        if (Objects.isNull(user)) {
            throw new ServiceException("用户不存在");
        }

        // 兼容 phone / mail
        String unbindType = AuthConstants.normalizeChannel(request.getUnbindType());
        String code = request.getCode();

        // 手机号/邮箱解绑需要验证码，从当前用户获取目标地址
        if (AuthConstants.BIND_TYPE_SMS.equals(unbindType)) {
            String phone = user.getPhonenumber();
            if (StrUtil.isBlank(phone)) {
                throw new ServiceException("您未绑定手机号");
            }
            validateCode(AuthConstants.SCENE_UNBIND, phone, unbindType, code);
            unbindPhone(user);
        } else if (AuthConstants.BIND_TYPE_EMAIL.equals(unbindType)) {
            String email = user.getEmail();
            if (StrUtil.isBlank(email)) {
                throw new ServiceException("您未绑定邮箱");
            }
            validateCode(AuthConstants.SCENE_UNBIND, email, unbindType, code);
            unbindEmail(user);
        } else if (AuthConstants.BIND_TYPE_WECHAT.equals(unbindType)) {
            // 微信解绑也要求二次验证码（优先手机号，其次邮箱），
            //   仅依赖登录态时会话被劫持后可直接解绑本人微信再换绑他人。
            if (StrUtil.isBlank(code)) {
                throw new ServiceException("验证码不能为空");
            }
            String verifyTarget;
            String verifyType;
            if (StrUtil.isNotBlank(user.getPhonenumber())) {
                verifyTarget = user.getPhonenumber();
                verifyType = AuthConstants.BIND_TYPE_SMS;
            } else if (StrUtil.isNotBlank(user.getEmail())) {
                verifyTarget = user.getEmail();
                verifyType = AuthConstants.BIND_TYPE_EMAIL;
            } else {
                throw new ServiceException("请先绑定手机号或邮箱再解绑微信");
            }
            validateCode(AuthConstants.SCENE_UNBIND, verifyTarget, verifyType, code);
            checkLoginMethodAfterUnbind(user, unbindType);
            unbindSocial(user, unbindType);
        } else {
            throw new ServiceException("解绑类型错误");
        }

        log.info("账号解绑成功: userId={}, unbindType={}", userId, unbindType);
    }

    /**
     * 检查解绑后是否至少有一种登录方式
     * 注意：userName 是随机生成的，不作为登录方式判断
     *
     * @param user       用户信息
     * @param unbindType 解绑类型
     */
    private void checkLoginMethodAfterUnbind(SysUser user, String unbindType) {
        boolean hasLoginMethod = false;

        // 检查手机号登录方式（解绑手机号时跳过）
        if (!AuthConstants.BIND_TYPE_SMS.equals(unbindType)) {
            hasLoginMethod = StrUtil.isNotBlank(user.getPhonenumber());
        }

        // 检查邮箱登录方式（解绑邮箱时跳过）
        if (!hasLoginMethod && !AuthConstants.BIND_TYPE_EMAIL.equals(unbindType)) {
            hasLoginMethod = StrUtil.isNotBlank(user.getEmail());
        }

        // 检查第三方登录方式
        if (!hasLoginMethod) {
            LambdaQueryWrapper<AidUserSocial> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(AidUserSocial::getUserId, user.getUserId())
                    .eq(AidUserSocial::getDelFlag, "0");
            // 如果解绑的是第三方，排除当前要解绑的平台
            if (AuthConstants.BIND_TYPE_WECHAT.equals(unbindType)) {
                queryWrapper.ne(AidUserSocial::getPlatformSource, unbindType);
            }
            Long count = userSocialMapper.selectCount(queryWrapper);
            hasLoginMethod = count > 0;
        }

        if (!hasLoginMethod) {
            throw new ServiceException("至少保留一种登录方式");
        }
    }

    /**
     * 解绑手机号
     */
    private void unbindPhone(SysUser user) {
        checkLoginMethodAfterUnbind(user, AuthConstants.BIND_TYPE_SMS);

        // 使用专门的解绑方法（可以把字段置为 null）
        userService.unbindPhonenumber(user.getUserId());
    }

    /**
     * 解绑邮箱
     */
    private void unbindEmail(SysUser user) {
        checkLoginMethodAfterUnbind(user, AuthConstants.BIND_TYPE_EMAIL);

        // 使用专门的解绑方法（可以把字段置为 null）
        userService.unbindEmail(user.getUserId());
    }

    /**
     * 解绑第三方账号（硬删除）
     */
    private void unbindSocial(SysUser user, String unbindType) {
        LambdaQueryWrapper<AidUserSocial> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AidUserSocial::getUserId, user.getUserId())
                .eq(AidUserSocial::getPlatformSource, unbindType)
                .eq(AidUserSocial::getDelFlag, "0");
        AidUserSocial social = userSocialMapper.selectOne(queryWrapper);

        if (Objects.isNull(social)) {
            throw new ServiceException("您未绑定该第三方账号");
        }

        // 硬删除绑定记录
        userSocialMapper.deleteById(social.getId());
    }

    /**
     * 注销脱敏拼接前的截断：保证「原值 + 后缀」不超过列长，超长部分截掉原值尾部。
     *
     * @param original  原值
     * @param maxLength 列长上限
     * @param suffix    脱敏后缀
     * @return 截断后拼好后缀的值
     */
    private String truncateForSuffix(String original, int maxLength, String suffix) {
        String base = Objects.isNull(original) ? "" : original;
        int keep = Math.max(maxLength - suffix.length(), 0);
        if (base.length() > keep) {
            base = base.substring(0, keep);
        }
        return base + suffix;
    }
}
