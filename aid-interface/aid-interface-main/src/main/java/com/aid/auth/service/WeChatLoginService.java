package com.aid.auth.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aid.aid.domain.AidUserProfile;
import com.aid.aid.domain.AidUserSocial;
import com.aid.aid.mapper.AidUserProfileMapper;
import com.aid.aid.mapper.AidUserSocialMapper;
import com.aid.aid.domain.vo.LoginVO;
import com.aid.aid.domain.vo.UserInfoVO;
import com.aid.aid.domain.vo.UserSocialVO;
import com.aid.aid.service.IAidConfigService;
import com.aid.common.constant.AuthConstants;
import com.aid.common.constant.Constants;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.SecurityUtils;
import com.aid.common.utils.ip.IpUtils;
import com.aid.framework.manager.AsyncManager;
import com.aid.framework.manager.factory.AsyncFactory;
import com.aid.common.core.service.TokenService;
import com.aid.core.service.ISysUserService;
import com.aid.promotion.service.IInviteService;
import com.aid.promotion.service.IRegisterBonusService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.bean.result.WxMpUser;
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
 * 微信扫码登录服务
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class WeChatLoginService {

    /**
     * 二维码有效期（秒）
     */
    private static final int QRCODE_EXPIRE_SECONDS = 300;

    /**
     * Redis key 前缀
     */
    private static final String LOGIN_KEY_PREFIX = "wechat_login:";

    /**
     * 等待扫码状态
     */
    private static final String STATUS_WAITING = "WAITING";

    /**
     * 已扫码处理中状态
     */
    private static final String STATUS_SCANNED = "SCANNED";

    /**
     * 登录成功状态
     */
    private static final String STATUS_SUCCESS = "SUCCESS";

    /**
     * 二维码已过期状态
     */
    private static final String STATUS_EXPIRED = "EXPIRED";

    /**
     * 登录或绑定失败状态
     */
    private static final String STATUS_FAIL = "FAIL";

    /**
     * 邀请码暂存最大长度（防御超长脏数据，实际格式在注册绑定时校验）
     */
    private static final int INVITE_CODE_MAX_LENGTH = 32;

    @Resource
    private RedisCache redisCache;

    @Resource
    private ISysUserService userService;

    @Resource
    private TokenService tokenService;

    @Resource
    private AidUserSocialMapper userSocialMapper;

    @Resource
    private AidUserProfileMapper userProfileMapper;

    @Resource
    private IAidConfigService aidConfigService;

    /** 媒体URL归一化：入库前把本站 CDN/本地全链接头像剥成相对路径，禁止 sys_user.avatar 存全链接 */
    @Resource
    private com.aid.common.aid.oss.util.MediaUrlResolver mediaUrlResolver;

    /** 多端在线策略执行器 */
    @Resource
    private com.aid.auth.policy.OnlineSessionPolicy onlineSessionPolicy;

    /** 邀请服务：新用户注册瞬间绑定邀请关系（静默，绝不阻断注册） */
    @Resource
    private IInviteService inviteService;

    /** 注册送积分服务：事务提交后发放，幂等一人一次 */
    @Resource
    private IRegisterBonusService registerBonusService;

    /**
     * 初始化登录状态
     *
     * @param sceneStr   场景值
     * @param inviteCode 邀请码（可选；随登录会话暂存，扫码注册新用户时绑定邀请关系）
     */
    public void initLoginStatus(String sceneStr, String inviteCode) {
        String cacheKey = LOGIN_KEY_PREFIX + sceneStr;
        Map<String, Object> loginData = new HashMap<>();
        loginData.put("status", STATUS_WAITING);
        loginData.put("createTime", System.currentTimeMillis());
        // 邀请码只做长度防御（防超长脏数据入 Redis），有效性在注册绑定时统一校验
        if (StrUtil.isNotBlank(inviteCode) && inviteCode.trim().length() <= INVITE_CODE_MAX_LENGTH) {
            loginData.put("inviteCode", inviteCode.trim());
        }
        redisCache.setCacheObject(cacheKey, loginData, QRCODE_EXPIRE_SECONDS, TimeUnit.SECONDS);
        log.info("微信登录状态初始化: sceneStr={}", sceneStr);
    }

    /**
     * 处理扫码事件
     *
     * @param sceneStr 场景值
     * @param openId   微信OpenID
     * @param wxMpUser 微信用户信息
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleScan(String sceneStr, String openId, WxMpUser wxMpUser) {
        String cacheKey = LOGIN_KEY_PREFIX + sceneStr;

        Map<String, Object> loginData = redisCache.<Map<String, Object>>getCacheObject(cacheKey);
        if (loginData == null) {
            log.warn("微信登录状态已失效: sceneStr={}", sceneStr);
            return;
        }
        String currentStatus = (String) loginData.get("status");
        if (STATUS_SUCCESS.equals(currentStatus) || STATUS_FAIL.equals(currentStatus)) {
            log.info("微信登录扫码状态已结束: sceneStr={}, status={}", sceneStr, currentStatus);
            return;
        }

        // 先标记已扫码，让前端轮询可以立刻切换到处理中态。
        loginData.put("status", STATUS_SCANNED);
        loginData.put("scanTime", System.currentTimeMillis());
        refreshCacheWithRemainingExpire(cacheKey, loginData);

        SysUser existUser = findUserByOpenId(openId);

        if (Objects.nonNull(existUser)) {
            // 微信登录必须校验账号状态，避免绕过停用 / 删除限制
            if (!"0".equals(existUser.getStatus()) || !"0".equals(existUser.getDelFlag())) {
                log.warn("微信扫码登录拒绝：账号已停用或被删除, sceneStr={}, userId={}, status={}, delFlag={}",
                        sceneStr, existUser.getUserId(), existUser.getStatus(), existUser.getDelFlag());
                loginData.put("status", STATUS_FAIL);
                loginData.put("message", "账号已停用");
                refreshCacheWithRemainingExpire(cacheKey, loginData);
                return;
            }

            // 已绑定用户，直接完成登录
            loginData.put("status", STATUS_SUCCESS);
            loginData.put("userId", existUser.getUserId());
            loginData.put("openId", openId);
            loginData.put("nickname", wxMpUser.getNickname());

            LoginUser loginUser = buildLoginUser(existUser);
            String token = tokenService.createToken(loginUser);
            loginData.put("token", token);
            // 执行多端在线策略
            onlineSessionPolicy.enforce(loginUser.getUserId(), loginUser.getToken());

            log.info("微信扫码登录成功(已绑定用户): sceneStr={}, openIdHash={}, userId={}",
                    sceneStr, hashSensitive(openId), existUser.getUserId());
        } else {
            // 未绑定用户，自动注册（携带二维码会话中暂存的邀请码）
            String inviteCode = (String) loginData.get("inviteCode");
            SysUser newUser = registerWechatUser(openId, wxMpUser, inviteCode);

            loginData.put("status", STATUS_SUCCESS);
            loginData.put("userId", newUser.getUserId());
            loginData.put("openId", openId);
            loginData.put("nickname", wxMpUser.getNickname());

            LoginUser loginUser = buildLoginUser(newUser);
            String token = tokenService.createToken(loginUser);
            loginData.put("token", token);
            // 执行多端在线策略
            onlineSessionPolicy.enforce(loginUser.getUserId(), loginUser.getToken());

            log.info("微信扫码登录成功(新注册用户): sceneStr={}, openIdHash={}, userId={}",
                    sceneStr, hashSensitive(openId), newUser.getUserId());
        }

        // 更新缓存状态（保持剩余有效期）
        refreshCacheWithRemainingExpire(cacheKey, loginData);
    }

    /**
     * 检查登录状态
     *
     * @param sceneStr 场景值
     * @return 登录状态
     */
    public AjaxResult checkLoginStatus(String sceneStr) {
        String cacheKey = LOGIN_KEY_PREFIX + sceneStr;
        Map<String, Object> loginData = redisCache.<Map<String, Object>>getCacheObject(cacheKey);

        if (loginData == null) {
            return AjaxResult.error("二维码已失效，请刷新重试", buildStatusData(STATUS_EXPIRED, 0L));
        }

        String status = (String) loginData.get("status");
        long expire = redisCache.getExpire(cacheKey);

        if (STATUS_WAITING.equals(status)) {
            return AjaxResult.warn("请使用微信扫码", buildStatusData(STATUS_WAITING, expire));
        }

        if (STATUS_SCANNED.equals(status)) {
            return AjaxResult.warn("已扫码，登录处理中", buildStatusData(STATUS_SCANNED, expire));
        }

        // 扫码时账号被禁用或被删除会写入 FAIL 状态，前端需明确感知
        if (STATUS_FAIL.equals(status)) {
            String message = (String) loginData.get("message");
            redisCache.deleteObject(cacheKey);
            return AjaxResult.error(StrUtil.isBlank(message) ? "登录失败" : message, buildStatusData(STATUS_FAIL, 0L));
        }

        if (STATUS_SUCCESS.equals(status)) {
            // 登录成功，构建完整响应（和其他登录方式一致）
            String token = (String) loginData.get("token");
            Long userId = (Long) loginData.get("userId");

            SysUser user = userService.selectUserById(userId);
            // 再次校验状态：极端并发场景下扫码生成 token 后用户可能被禁用
            if (user == null || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag())) {
                redisCache.deleteObject(cacheKey);
                return AjaxResult.error("账号已停用", buildStatusData(STATUS_FAIL, 0L));
            }

            LoginVO loginVO = buildLoginVO(user, token);

            // 登录成功后删除缓存
            redisCache.deleteObject(cacheKey);

            // 返回和其他登录方式一致的数据结构
            Map<String, Object> data = new HashMap<>();
            data.put("status", STATUS_SUCCESS);
            data.put("token", loginVO.getToken());
            data.put("userInfo", loginVO.getUserInfo());
            data.put("social", loginVO.getSocial());

            return AjaxResult.success("登录成功", data);
        }

        return AjaxResult.error("登录状态异常");
    }

    /**
     * 标记登录扫码失败，避免前端长时间停留在处理中态。
     */
    public void markLoginFailed(String sceneStr, String message) {
        markStatusFailed(LOGIN_KEY_PREFIX + sceneStr, message);
    }

    /**
     * 构建登录响应（和其他登录方式一致）
     *
     * 手机号 / 邮箱 / 真实姓名 / 身份证在出参做脱敏，与 AuthService 保持一致。
     */
    private LoginVO buildLoginVO(SysUser user, String token) {
        Long userId = user.getUserId();

        AidUserProfile profile = getUserProfile(userId);

        List<UserSocialVO> socialList = getUserSocialList(userId);

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

        return LoginVO.builder()
                .token(token)
                .userInfo(userInfoBuilder.build())
                .social(socialList)
                .build();
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
     * 敏感标识哈希（仅用于日志），SHA-256 后取前 8 位 hex。
     */
    private String hashSensitive(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4 && i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "***";
        }
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
     * 根据OpenID查询已绑定用户
     *
     * @param openId 微信OpenID
     * @return 用户信息，未绑定返回null
     */
    private SysUser findUserByOpenId(String openId) {
        if (StrUtil.isBlank(openId)) {
            return null;
        }

        LambdaQueryWrapper<AidUserSocial> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AidUserSocial::getPlatformSource, "wechat")
                .eq(AidUserSocial::getOpenid, openId)
                .eq(AidUserSocial::getDelFlag, "0");

        AidUserSocial social = userSocialMapper.selectOne(queryWrapper);
        if (Objects.nonNull(social)) {
            return userService.selectUserById(social.getUserId());
        }

        return null;
    }

    /**
     * 自动静默注册微信用户。
     *
     * @param openId     微信OpenID
     * @param wxMpUser   微信用户信息
     * @param inviteCode 邀请码（可选，来自二维码会话暂存，仅注册瞬间绑定邀请关系）
     * @return 新注册的用户
     */
    protected SysUser registerWechatUser(String openId, WxMpUser wxMpUser, String inviteCode) {
        // 昵称做 null/长度/XSS 兜底，保持"微信用户_xxx"兜底语义
        String rawNick = wxMpUser == null ? null : wxMpUser.getNickname();
        String nickName = sanitizeWechatNickName(rawNick);

        // 头像 URL 做协议 + 长度校验，非 https 或超过 500 字符则丢弃
        String avatar = sanitizeAvatarUrl(wxMpUser == null ? null : wxMpUser.getHeadImgUrl());
        // 微信未返回可用头像时，用后台配置的默认头像兜底（aid_config: default_avatar/urls，最多5张随机选一张）
        if (avatar == null || avatar.isBlank()) {
            try {
                avatar = com.aid.auth.util.SilentRegistrationUtils.pickRandomAvatar(
                        aidConfigService.getConfigValue("default_avatar", "urls"));
            } catch (Exception ignore) {
                avatar = "";
            }
        }
        // 入库前归一化为相对路径：本站 CDN/本地全链接剥成相对路径，禁止 sys_user.avatar 存全链接；
        // 微信外链头像（非本站域名）toRelativePath 会原样返回，读取层 @MediaUrl 直接透传。
        avatar = mediaUrlResolver.toRelativePath(avatar);

        SysUser user = new SysUser();
        user.setUserName(generateUserName());
        user.setNickName(nickName);
        // 静默注册：使用不可用强随机密码，用户只能通过验证码或微信再次登录
        user.setPassword(SecurityUtils.encryptPassword(
                com.aid.auth.util.SilentRegistrationUtils.generateUnusablePassword()));
        user.setAvatar(avatar);
        // 性别默认设置为未知
        user.setSex("2");
        user.setStatus("0"); // 正常
        user.setDelFlag("0"); // 未删除
        // 和短信/邮箱静默注册一致，分配默认部门
        user.setDeptId(com.aid.auth.util.SilentRegistrationUtils.DEFAULT_DEPT_ID);
        user.setCreateBy("wechat");
        user.setCreateTime(DateUtils.getNowDate());
        user.setLoginIp(IpUtils.getIpAddr());
        user.setLoginDate(DateUtils.getNowDate());
        // 分配默认角色
        user.setRoleIds(new Long[]{com.aid.auth.util.SilentRegistrationUtils.DEFAULT_ROLE_ID});

        // 走完整静默注册路径：内部会插入用户、关联角色/岗位、初始化 aid_user_profile
        int rows = userService.insertUser(user);
        if (rows <= 0) {
            throw new ServiceException("注册失败");
        }

        saveWechatBind(user.getUserId(), openId, wxMpUser);

        // 注册瞬间绑定邀请关系（静默，注册事务内，回滚即解除；老用户扫码登录不会走到这里）
        inviteService.bindOnRegister(user.getUserId(), inviteCode, AuthConstants.BIND_TYPE_WECHAT);
        // 注册送积分（静默，事务提交后发放，幂等一人一次；微信渠道可在后台单独开关）
        registerBonusService.grantAfterRegister(user.getUserId(), AuthConstants.BIND_TYPE_WECHAT);

        // 记录注册日志（不打印 nickName 中可能含 PII，仅打 userId）
        AsyncManager.me().execute(AsyncFactory.recordLogininfor(
                user.getUserName(), Constants.LOGIN_SUCCESS, "微信扫码登录自动注册"));

        log.info("微信用户自动注册成功: openIdHash={}, userId={}", hashSensitive(openId), user.getUserId());

        return user;
    }

    /**
     * 清洗微信昵称：null/空 → 随机昵称；去控制字符 + 危险字符；截断 30 字符。
     */
    private String sanitizeWechatNickName(String raw) {
        if (!StrUtil.isNotBlank(raw)) {
            return "微信用户_" + RandomUtil.randomString(6);
        }
        // 去除控制字符 (ISO 控制位) 与常见危险字符
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c)) {
                continue;
            }
            if (c == '<' || c == '>' || c == '"' || c == '\'' || c == '`') {
                continue;
            }
            sb.append(c);
        }
        String cleaned = sb.toString().trim();
        if (cleaned.isEmpty()) {
            return "微信用户_" + RandomUtil.randomString(6);
        }
        // 截断到 30 字符（sys_user.nick_name 通常 30 字符限制）
        if (cleaned.length() > 30) {
            cleaned = cleaned.substring(0, 30);
        }
        return cleaned;
    }

    /**
     * 清洗微信头像 URL：非空 + https + 长度 <= 500 才保留。
     */
    private String sanitizeAvatarUrl(String raw) {
        if (!StrUtil.isNotBlank(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 500) {
            return null;
        }
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            return null;
        }
        return trimmed;
    }

    /**
     * 保存微信绑定关系。
     *
     * @param userId   用户ID
     * @param openId   微信OpenID
     * @param wxMpUser 微信用户信息
     */
    private void saveWechatBind(Long userId, String openId, WxMpUser wxMpUser) {
        // 防御性：再次检查同 openid 是否已存在活跃绑定
        LambdaQueryWrapper<AidUserSocial> dupWrapper = new LambdaQueryWrapper<>();
        dupWrapper.eq(AidUserSocial::getPlatformSource, "wechat")
                .eq(AidUserSocial::getOpenid, openId)
                .eq(AidUserSocial::getDelFlag, "0");
        if (Objects.nonNull(userSocialMapper.selectOne(dupWrapper))) {
            log.warn("微信绑定关系重复, openIdHash={}, userId={}", hashSensitive(openId), userId);
            throw new ServiceException("该微信已被绑定");
        }

        AidUserSocial social = new AidUserSocial();
        social.setUserId(userId);
        social.setPlatformSource("wechat");
        social.setOpenid(openId);
        social.setUnionid(wxMpUser == null ? null : wxMpUser.getUnionId());
        social.setDelFlag("0");
        social.setCreateBy("wechat");
        social.setCreateTime(DateUtils.getNowDate());

        try {
            userSocialMapper.insert(social);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 依赖唯一索引兜底：并发竞态下两请求同时插入由数据库收口
            log.warn("微信绑定唯一索引冲突, openIdHash={}, userId={}", hashSensitive(openId), userId);
            throw new ServiceException("该微信已被绑定");
        }

        log.info("保存微信绑定关系: userId={}, openIdHash={}", userId, hashSensitive(openId));
    }

    /**
     * 生成用户名（委托 SilentRegistrationUtils 统一处理）
     */
    private String generateUserName() {
        return com.aid.auth.util.SilentRegistrationUtils.generateUserName();
    }

    /**
     * 构建登录用户对象
     */
    private LoginUser buildLoginUser(SysUser user) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUser(user);
        loginUser.setUserId(user.getUserId());

        userService.updateLoginInfo(user.getUserId(), IpUtils.getIpAddr(), DateUtils.getNowDate());

        return loginUser;
    }
    /**
     * 绑定二维码 Redis key 前缀
     */
    private static final String BIND_KEY_PREFIX = "wechat_bind:";

    /**
     * 初始化绑定状态
     *
     * @param sceneStr 场景值
     * @param userId   当前登录用户ID
     */
    public void initBindStatus(String sceneStr, Long userId) {
        String cacheKey = BIND_KEY_PREFIX + sceneStr;
        Map<String, Object> bindData = new HashMap<>();
        bindData.put("status", STATUS_WAITING);
        bindData.put("userId", userId);
        bindData.put("createTime", System.currentTimeMillis());
        redisCache.setCacheObject(cacheKey, bindData, QRCODE_EXPIRE_SECONDS, TimeUnit.SECONDS);
        log.info("微信绑定状态初始化: sceneStr={}, userId={}", sceneStr, userId);
    }

    /**
     * 处理扫码绑定事件
     *
     * @param sceneStr 场景值
     * @param openId   微信OpenID
     * @param wxMpUser 微信用户信息
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleBindScan(String sceneStr, String openId, WxMpUser wxMpUser) {
        String cacheKey = BIND_KEY_PREFIX + sceneStr;

        Map<String, Object> bindData = redisCache.<Map<String, Object>>getCacheObject(cacheKey);
        if (bindData == null) {
            log.warn("微信绑定状态已失效: sceneStr={}", sceneStr);
            return;
        }
        String currentStatus = (String) bindData.get("status");
        if (STATUS_SUCCESS.equals(currentStatus) || STATUS_FAIL.equals(currentStatus)) {
            log.info("微信绑定扫码状态已结束: sceneStr={}, status={}", sceneStr, currentStatus);
            return;
        }

        Long userId = (Long) bindData.get("userId");
        if (userId == null) {
            log.warn("微信绑定用户ID为空: sceneStr={}", sceneStr);
            return;
        }

        // 先标记已扫码，让绑定页也能展示处理中态。
        bindData.put("status", STATUS_SCANNED);
        bindData.put("scanTime", System.currentTimeMillis());
        refreshCacheWithRemainingExpire(cacheKey, bindData);

        // 检查该 openid 是否已被其他用户绑定
        LambdaQueryWrapper<AidUserSocial> checkWrapper = new LambdaQueryWrapper<>();
        checkWrapper.eq(AidUserSocial::getPlatformSource, "wechat")
                .eq(AidUserSocial::getOpenid, openId)
                .eq(AidUserSocial::getDelFlag, "0");
        AidUserSocial existBind = userSocialMapper.selectOne(checkWrapper);
        if (Objects.nonNull(existBind) && !Objects.equals(existBind.getUserId(), userId)) {
            bindData.put("status", STATUS_FAIL);
            bindData.put("message", "该微信已被其他账号绑定");
            refreshCacheWithRemainingExpire(cacheKey, bindData);
            log.warn("微信绑定失败: openid已被其他用户绑定, sceneStr={}, openIdHash={}",
                    sceneStr, hashSensitive(openId));
            return;
        }

        // 检查当前用户是否已绑定微信
        LambdaQueryWrapper<AidUserSocial> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AidUserSocial::getUserId, userId)
                .eq(AidUserSocial::getPlatformSource, "wechat")
                .eq(AidUserSocial::getDelFlag, "0");
        AidUserSocial existSocial = userSocialMapper.selectOne(queryWrapper);
        if (Objects.nonNull(existSocial)) {
            bindData.put("status", STATUS_FAIL);
            bindData.put("message", "您已绑定微信，请先解绑后再绑定新微信");
            refreshCacheWithRemainingExpire(cacheKey, bindData);
            log.warn("微信绑定失败: 用户已绑定微信, sceneStr={}, userId={}", sceneStr, userId);
            return;
        }

        AidUserSocial social = new AidUserSocial();
        social.setUserId(userId);
        social.setPlatformSource("wechat");
        social.setOpenid(openId);
        social.setUnionid(wxMpUser.getUnionId());
        social.setDelFlag("0");
        social.setCreateBy("wechat-bind");
        social.setCreateTime(DateUtils.getNowDate());
        try {
            userSocialMapper.insert(social);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 唯一索引兜底
            bindData.put("status", STATUS_FAIL);
            bindData.put("message", "该微信已被绑定");
            refreshCacheWithRemainingExpire(cacheKey, bindData);
            log.warn("微信绑定唯一索引冲突, sceneStr={}, openIdHash={}, userId={}",
                    sceneStr, hashSensitive(openId), userId);
            return;
        }

        bindData.put("status", STATUS_SUCCESS);
        bindData.put("openId", openId);
        bindData.put("nickname", wxMpUser.getNickname());

        refreshCacheWithRemainingExpire(cacheKey, bindData);

        log.info("微信绑定成功: sceneStr={}, openIdHash={}, userId={}",
                sceneStr, hashSensitive(openId), userId);
    }

    /**
     * 检查绑定状态
     *
     * 校验 sceneStr 中存储的 userId 必须与当前会话 userId 一致，防止他人拿到 sceneStr 消费别人的绑定状态。
     *
     * @param sceneStr 场景值
     * @return 绑定状态
     */
    public AjaxResult checkBindStatus(String sceneStr) {
        String cacheKey = BIND_KEY_PREFIX + sceneStr;
        Map<String, Object> bindData = redisCache.<Map<String, Object>>getCacheObject(cacheKey);

        if (bindData == null) {
            return AjaxResult.error("二维码已失效，请刷新重试", buildStatusData(STATUS_EXPIRED, 0L));
        }

        // scene 归属校验，避免他人轮询消费
        Long currentUserId = SecurityUtils.getUserId();
        Long bindUserId = (Long) bindData.get("userId");
        if (currentUserId == null || bindUserId == null || !Objects.equals(currentUserId, bindUserId)) {
            log.warn("非法访问绑定状态: sceneStr={}, currentUserId={}, bindUserId={}",
                    sceneStr, currentUserId, bindUserId);
            // 不删除缓存（让真正的归属用户继续轮询），但拒绝访问
            return AjaxResult.error("无权访问该绑定状态");
        }

        String status = (String) bindData.get("status");
        long expire = redisCache.getExpire(cacheKey);

        if (STATUS_WAITING.equals(status)) {
            return AjaxResult.warn("请使用微信扫码", buildStatusData(STATUS_WAITING, expire));
        }

        if (STATUS_SCANNED.equals(status)) {
            return AjaxResult.warn("已扫码，绑定处理中", buildStatusData(STATUS_SCANNED, expire));
        }

        if (STATUS_FAIL.equals(status)) {
            String message = (String) bindData.get("message");
            redisCache.deleteObject(cacheKey);
            return AjaxResult.error(message != null ? message : "绑定失败", buildStatusData(STATUS_FAIL, 0L));
        }

        if (STATUS_SUCCESS.equals(status)) {
            redisCache.deleteObject(cacheKey);
            return AjaxResult.success("绑定成功", buildStatusData(STATUS_SUCCESS, 0L));
        }

        return AjaxResult.error("绑定状态异常");
    }

    /**
     * 标记绑定扫码失败，避免前端长时间停留在处理中态。
     */
    public void markBindFailed(String sceneStr, String message) {
        markStatusFailed(BIND_KEY_PREFIX + sceneStr, message);
    }

    /**
     * 将扫码会话置为失败状态，并保持原有剩余 TTL。
     */
    private void markStatusFailed(String cacheKey, String message) {
        Map<String, Object> data = redisCache.<Map<String, Object>>getCacheObject(cacheKey);
        if (data == null) {
            return;
        }
        data.put("status", STATUS_FAIL);
        data.put("message", StrUtil.isBlank(message) ? "处理失败" : message);
        refreshCacheWithRemainingExpire(cacheKey, data);
    }

    /**
     * 使用当前 Redis 剩余 TTL 刷新扫码会话，避免状态更新延长二维码有效期。
     */
    private void refreshCacheWithRemainingExpire(String cacheKey, Map<String, Object> data) {
        long expire = redisCache.getExpire(cacheKey);
        if (expire > 0) {
            redisCache.setCacheObject(cacheKey, data, (int) expire, TimeUnit.SECONDS);
        }
    }

    /**
     * 构建扫码状态响应体，前端统一读取 data.status 驱动页面状态。
     */
    private Map<String, Object> buildStatusData(String status, long expireSeconds) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", status);
        if (expireSeconds > 0) {
            data.put("expireSeconds", expireSeconds);
        }
        return data;
    }
}
