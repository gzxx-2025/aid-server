package com.aid.common.aid.redis;

/**
 * 统一 Redis Key 命名空间常量。
 *
 * @author 视觉AID
 */
public final class RedisKeys
{
    private RedisKeys() {}
    /** 登录失败累计计数：aid:auth:login:fail:{username} */
    public static String authLoginFail(String username)
    {
        return "aid:auth:login:fail:" + username;
    }

    /** 登录失败锁定：aid:auth:login:lock:{username} */
    public static String authLoginLock(String username)
    {
        return "aid:auth:login:lock:" + username;
    }

    /** 实名认证冷却：aid:auth:real:cooldown:{userId} */
    public static String authRealCooldown(Long userId)
    {
        return "aid:auth:real:cooldown:" + userId;
    }
    /** 支付回调幂等锁：aid:pay:notify:{orderNo} */
    public static String payNotifyLock(String orderNo)
    {
        return "aid:pay:notify:" + orderNo;
    }
    public static String extractLock(Long projectId, Long episodeId)
    {
        return "aid:extract:lock:" + projectId + ":" + (episodeId == null ? "0" : episodeId);
    }

    public static String extractCancelFlag(Long taskId)
    {
        return "aid:extract:cancel:" + taskId;
    }

    public static String extractBillingLock(Long taskId)
    {
        return "aid:extract:billing:lock:" + taskId;
    }
    public static String mediaBillingLock(Long taskId)
    {
        return "aid:media:billing:lock:" + taskId;
    }

    public static String mediaConcurrentGlobal()
    {
        return "aid:media:concurrent:global";
    }

    public static String mediaConcurrentUser(Long userId)
    {
        return "aid:media:concurrent:user:" + (userId == null ? "anonymous" : userId);
    }
    public static String formGenerateLock(Long assetId)
    {
        return "aid:form:generate:lock:" + assetId;
    }

    public static String formImageLock(Long formId)
    {
        return "aid:form:image:lock:" + formId;
    }

    public static String formCardLock(Long imageId)
    {
        return "aid:form:card:lock:" + imageId;
    }

    public static String formUpscaleLock(Long imageId)
    {
        return "aid:form:upscale:lock:" + imageId;
    }

    public static String formMultiViewLock(Long formId)
    {
        return "aid:form:multi-view:lock:" + formId;
    }

    public static String formEditChatLock(Long formId)
    {
        return "aid:form:edit-chat:lock:" + formId;
    }
}
