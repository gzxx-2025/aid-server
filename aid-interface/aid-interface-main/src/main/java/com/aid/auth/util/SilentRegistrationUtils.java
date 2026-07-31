package com.aid.auth.util;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 静默注册工具类。
 *
 * @author 视觉AID
 */
public final class SilentRegistrationUtils {

    /**
     * 默认部门 ID
     */
    public static final Long DEFAULT_DEPT_ID = 100L;

    /**
     * 默认角色 ID
     */
    public static final Long DEFAULT_ROLE_ID = 2L;

    /**
     * 默认头像目录
     */
    public static final String DEFAULT_AVATAR_PATH = "/profile/avatar/default";

    /**
     * 默认头像编号上限（含）
     */
    private static final int DEFAULT_AVATAR_MAX = 10;

    /**
     * 共享的 SecureRandom 实例。SecureRandom 本身线程安全。
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private SilentRegistrationUtils() {
        // 工具类禁止实例化
    }

    /**
     * 生成不可用的强随机密码。
     * 静默注册用户不应通过密码登录，仅能走验证码；
     * 如果用户想用密码登录，需要走「忘记密码」流程主动设置。
     *
     * @return 明文强随机密码（调用方需自行 BCrypt 加密后入库）
     */
    public static String generateUnusablePassword() {
        // 随机字节取 16，明文长度控制在 BCrypt 72 字节上限内（32位UUID + 1分隔符 + 22位Base64 = 55字节）
        byte[] randomBytes = new byte[16];
        SECURE_RANDOM.nextBytes(randomBytes);
        return IdUtil.fastSimpleUUID()
                + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * 生成用户名。格式：u_ + 当前时间戳后 8 位 + 随机 4 位
     * 例：u_03219012_x7k3
     */
    public static String generateUserName() {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String timestampSuffix = timestamp.substring(timestamp.length() - 8);
        String randomSuffix = RandomUtil.randomString(4);
        return "u_" + timestampSuffix + "_" + randomSuffix;
    }

    /**
     * 生成随机昵称：AID_xxxxxx（6 位随机字母数字）
     */
    public static String generateNickname() {
        return "AID_" + RandomUtil.randomString(6);
    }

    /**
     * 生成随机默认头像路径。传入配置前缀为空时使用 {@link #DEFAULT_AVATAR_PATH}。
     *
     * @param avatarPrefix 头像配置前缀（一般从 sys_config.default_avatar 读取）
     */
    public static String generateRandomAvatar(String avatarPrefix) {
        String prefix = (avatarPrefix == null || avatarPrefix.isBlank()) ? DEFAULT_AVATAR_PATH : avatarPrefix;
        int randomNum = RandomUtil.randomInt(1, DEFAULT_AVATAR_MAX + 1);
        return prefix + "/" + randomNum + ".png";
    }

    /**
     * 从「默认头像 URL 列表」中随机选取一张。
     *
     * @param csvUrls 逗号分隔的头像地址列表
     * @return 随机一张头像地址；无可用项时返回 ""
     */
    public static String pickRandomAvatar(String csvUrls) {
        if (csvUrls == null || csvUrls.isBlank()) {
            return "";
        }
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String s : csvUrls.split(",")) {
            if (s != null && !s.trim().isEmpty()) {
                list.add(s.trim());
            }
        }
        if (list.isEmpty()) {
            return "";
        }
        return list.get(RandomUtil.randomInt(0, list.size()));
    }
}
