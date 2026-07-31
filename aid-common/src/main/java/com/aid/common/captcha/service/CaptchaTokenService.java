package com.aid.common.captcha.service;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.aid.common.captcha.config.CaptchaProperties;
import com.aid.common.core.redis.RedisCache;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 二次验证 token 服务。
 *
 * 行为验证通过后签发一次性 token 存入 Redis；业务接口校验时一旦命中即删除，
 * 保证 token 一次性、防重放。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class CaptchaTokenService {

    @Resource
    private RedisCache redisCache;

    /**
     * 签发一次性 token。
     *
     * @param expireSeconds 有效期（秒）
     * @return token 字符串
     */
    public String issue(int expireSeconds) {
        // 生成无连字符的 UUID 作为 token
        String token = IdUtil.simpleUUID();
        String key = CaptchaProperties.REDIS_TOKEN_PREFIX + token;
        // 写入 Redis，值仅占位
        redisCache.setCacheObject(key, "1", expireSeconds, TimeUnit.SECONDS);
        return token;
    }

    /**
     * 消费 token：命中则删除并返回 true，否则返回 false。
     *
     * @param token 待校验 token
     * @return 是否校验通过
     */
    public boolean consume(String token) {
        // 空 token 直接失败
        if (StrUtil.isBlank(token)) {
            return false;
        }
        String key = CaptchaProperties.REDIS_TOKEN_PREFIX + token;
        try {
            // 不存在视为失败
            if (!redisCache.hasKey(key)) {
                return false;
            }
            // 命中即删，保证一次性
            redisCache.deleteObject(key);
            return true;
        } catch (Exception e) {
            // Redis 异常先 log，再按失败处理（token 校验从严）
            log.error("二次验证token校验异常: token={}", token, e);
            return false;
        }
    }
}
