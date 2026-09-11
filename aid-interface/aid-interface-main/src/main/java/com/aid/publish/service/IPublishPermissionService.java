package com.aid.publish.service;

/**
 * 作品发布权限校验Service接口
 *
 * @author 视觉AID
 */
public interface IPublishPermissionService
{
    /**
     * 校验用户是否允许发布作品，不允许时抛出业务异常。
     * 优先级：用户显式禁发 &gt; 白名单豁免 &gt; 发布总开关 &gt; 默认允许。
     *
     * @param userId 用户ID
     */
    void assertCanPublish(Long userId);
}
