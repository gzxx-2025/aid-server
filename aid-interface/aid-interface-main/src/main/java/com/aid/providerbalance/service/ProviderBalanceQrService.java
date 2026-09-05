package com.aid.providerbalance.service;

import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.wxlogin.core.WxLoginTemplateFactory;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.result.WxMpQrCodeTicket;
import me.chanjar.weixin.mp.bean.result.WxMpUser;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 后台余额提醒人微信扫码绑定。扫码场景与登录/用户绑定严格隔离。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderBalanceQrService {
    private static final String PREFIX = "provider_balance_qr:";
    private static final int EXPIRE_SECONDS = 300;

    private final RedisCache redisCache;
    private final WxLoginTemplateFactory wxLoginTemplateFactory;
    private final ProviderBalanceService balanceService;

    public Map<String, Object> create(Long ownerId, String recipientName, Object providerIds) {
        if (ownerId == null) throw new ServiceException("登录状态已失效");
        if (!wxLoginTemplateFactory.isEnabled()) throw new ServiceException("微信公众号未启用");
        String normalizedIds = balanceService.normalizeProviderIds(providerIds);
        balanceService.validateProviderIds(normalizedIds);
        try {
            WxMpService wxMpService = wxLoginTemplateFactory.getWxMpService();
            String scene = "pbal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            WxMpQrCodeTicket ticket = wxMpService.getQrcodeService().qrCodeCreateTmpTicket(scene, EXPIRE_SECONDS);
            Map<String, Object> state = new HashMap<>();
            state.put("status", "WAITING");
            state.put("ownerId", ownerId);
            state.put("recipientName", StrUtil.sub(StrUtil.trimToEmpty(recipientName), 0, 64));
            state.put("providerIds", normalizedIds);
            redisCache.setCacheObject(PREFIX + scene, state, EXPIRE_SECONDS, TimeUnit.SECONDS);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sceneStr", scene);
            result.put("qrCodeUrl", wxMpService.getQrcodeService().qrCodePictureUrl(ticket.getTicket()));
            result.put("expireSeconds", EXPIRE_SECONDS);
            return result;
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("创建余额提醒人微信二维码失败", ex);
            throw new ServiceException("二维码生成失败");
        }
    }

    public Map<String, Object> status(Long ownerId, String scene) {
        Map<String, Object> state = state(scene);
        if (state == null) return Map.of("status", "EXPIRED", "expireSeconds", 0);
        Long storedOwner = toLong(state.get("ownerId"));
        if (!Objects.equals(ownerId, storedOwner)) throw new ServiceException("无权访问该扫码状态");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", state.get("status"));
        result.put("expireSeconds", Math.max(0, redisCache.getExpire(PREFIX + scene)));
        if ("SUCCESS".equals(state.get("status"))) {
            result.put("recipientId", state.get("recipientId"));
            result.put("nickname", state.get("nickname"));
        }
        if ("FAIL".equals(state.get("status"))) {
            result.put("message", state.get("message"));
        }
        return result;
    }

    public void handleScan(String scene, String openId, WxMpUser user) {
        Map<String, Object> state = state(scene);
        if (state == null || List.of("SUCCESS", "FAIL").contains(String.valueOf(state.get("status")))) return;
        state.put("status", "SCANNED");
        refresh(scene, state);
        try {
            Long owner = toLong(state.get("ownerId"));
            String nickname = user == null ? "" : StrUtil.trimToEmpty(user.getNickname());
            Long recipientId = balanceService.saveWechatRecipient(
                    String.valueOf(state.get("recipientName")), openId, nickname,
                    String.valueOf(state.get("providerIds")), owner == null ? "system" : String.valueOf(owner));
            state.put("status", "SUCCESS");
            state.put("recipientId", recipientId);
            state.put("nickname", StrUtil.sub(nickname, 0, 64));
            refresh(scene, state);
        } catch (Exception ex) {
            log.error("余额提醒人微信扫码绑定失败: scene={}", scene, ex);
            markFailed(scene, "添加微信提醒人失败");
        }
    }

    public void markFailed(String scene, String message) {
        Map<String, Object> state = state(scene);
        if (state == null) return;
        state.put("status", "FAIL");
        state.put("message", StrUtil.blankToDefault(message, "绑定失败"));
        refresh(scene, state);
    }

    private Map<String, Object> state(String scene) {
        if (StrUtil.isBlank(scene) || !scene.matches("pbal_[a-zA-Z0-9]{16,32}")) return null;
        return redisCache.getCacheObject(PREFIX + scene, Map.class);
    }

    private void refresh(String scene, Map<String, Object> state) {
        long ttl = redisCache.getExpire(PREFIX + scene);
        if (ttl > 0) redisCache.setCacheObject(PREFIX + scene, state, (int) ttl, TimeUnit.SECONDS);
    }

    private Long toLong(Object value) {
        try { return value == null ? null : Long.valueOf(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }
}
