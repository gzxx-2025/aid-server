package com.aid.publish.service.impl;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aid.aid.domain.AidUserProfile;
import com.aid.aid.service.IAidConfigService;
import com.aid.aid.service.IAidPublishWhitelistService;
import com.aid.aid.service.IAidUserProfileService;
import com.aid.common.exception.ServiceException;
import com.aid.publish.service.IPublishPermissionService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 作品发布权限校验Service实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class PublishPermissionServiceImpl implements IPublishPermissionService
{
    /** 发布总开关配置分类 */
    private static final String CONFIG_CATEGORY_BASIC = "basic";

    /** 发布总开关配置键 */
    private static final String CONFIG_KEY_PUBLISH_ENABLED = "work_publish_enabled";

    /** 用户级发布权限：禁止 */
    private static final Integer USER_PUBLISH_DISABLED = 0;

    @Autowired
    private IAidConfigService aidConfigService;

    @Autowired
    private IAidUserProfileService aidUserProfileService;

    @Autowired
    private IAidPublishWhitelistService aidPublishWhitelistService;

    /**
     * 校验用户是否允许发布作品，不允许时抛出业务异常。
     * 优先级：用户显式禁发 &gt; 白名单豁免 &gt; 发布总开关 &gt; 默认允许。
     *
     * @param userId 用户ID
     */
    @Override
    public void assertCanPublish(Long userId)
    {
        if (Objects.isNull(userId))
        {
            log.error("发布权限校验失败，用户ID为空");
            throw new ServiceException("用户信息异常");
        }
        // 用户显式禁发：管理员针对该用户关闭发布权限，白名单也不豁免
        if (isUserExplicitlyDisabled(userId))
        {
            log.info("发布被拒绝，用户发布权限已被关闭: userId={}", userId);
            throw new ServiceException("发布权限已关闭");
        }
        // 白名单豁免：名单内用户不受发布总开关限制
        if (aidPublishWhitelistService.existsByUserId(userId))
        {
            return;
        }
        // 发布总开关：关闭后除白名单用户外禁止发布
        if (!isGlobalPublishEnabled())
        {
            log.info("发布被拒绝，发布总开关已关闭: userId={}", userId);
            throw new ServiceException("发布功能未开放");
        }
    }

    /**
     * 用户级发布权限是否被显式关闭（profile 缺失或字段为空按允许处理）
     *
     * @param userId 用户ID
     * @return true=已被禁发
     */
    private boolean isUserExplicitlyDisabled(Long userId)
    {
        AidUserProfile profile = aidUserProfileService.getByUserId(userId);
        return Objects.nonNull(profile)
                && Objects.equals(USER_PUBLISH_DISABLED, profile.getPublishEnabled());
    }

    /**
     * 读取发布总开关（配置缺失或异常按开启处理，避免误伤正常发布）
     *
     * @return true=允许发布
     */
    private boolean isGlobalPublishEnabled()
    {
        try
        {
            String value = aidConfigService.getConfigValue(CONFIG_CATEGORY_BASIC, CONFIG_KEY_PUBLISH_ENABLED);
            if (StrUtil.isBlank(value))
            {
                return true;
            }
            return Boolean.parseBoolean(value.trim());
        }
        catch (Exception ex)
        {
            log.error("读取发布总开关配置异常，按开启处理", ex);
            return true;
        }
    }
}
