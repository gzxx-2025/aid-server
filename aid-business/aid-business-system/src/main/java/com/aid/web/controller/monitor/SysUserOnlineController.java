package com.aid.web.controller.monitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.annotation.Log;
import com.aid.common.constant.CacheConstants;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.StringUtils;
import com.aid.system.domain.SysOnlineUser;
import com.aid.system.domain.SysUserOnline;
import com.aid.system.service.ISysUserOnlineService;

/**
 * 在线用户监控
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/monitor/online")
public class SysUserOnlineController extends BaseController
{
    @Autowired
    private ISysUserOnlineService userOnlineService;

    @Autowired
    private RedisCache redisCache;

    /** 多端在线策略执行器：复用 aid_config(login_policy) 的最大在线会话数配置 */
    @Autowired
    private com.aid.auth.policy.OnlineSessionPolicy onlineSessionPolicy;

    /**
     * 在线用户列表（按用户ID聚合）
     * 同一用户可能持有多个未过期Token，此处聚合为一个用户一行，明细Token挂在tokens下；
     * 同时对已过期/异常的会话进行自动清理，不再展示。
     */
    @PreAuthorize("@ss.hasPermi('monitor:online:list')")
    @GetMapping("/list")
    public TableDataInfo list(String ipaddr, String userName)
    {
        // 先按 aid_config(login_policy) 的最大在线会话数补偿清理超限会话（超出的按登录时间挤掉最旧的）
        onlineSessionPolicy.enforceAll();
        Collection<String> keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
        List<SysUserOnline> sessionList = new ArrayList<SysUserOnline>();
        long now = System.currentTimeMillis();
        for (String key : keys)
        {
            LoginUser user;
            try
            {
                // 兜底转换：兼容历史/异构序列化导致反序列化成 JSONObject 的会话，避免整表接口 500
                user = redisCache.getCacheObject(key, LoginUser.class);
            }
            catch (Exception e)
            {
                // 无法解析的会话（历史脏数据），跳过不展示，也不删除，避免误删未知数据
                logger.error("在线会话解析失败，已跳过, key={}, err={}", key, e.getMessage());
                continue;
            }
            // 自动清理：会话为空或无用户信息，直接从Redis删除，不再展示
            if (Objects.isNull(user) || Objects.isNull(user.getUser()))
            {
                redisCache.deleteObject(key);
                continue;
            }
            // 自动清理：会话已过期（防御性处理，正常情况Redis会按TTL自动清理）
            if (Objects.nonNull(user.getExpireTime()) && user.getExpireTime() > 0 && user.getExpireTime() < now)
            {
                redisCache.deleteObject(key);
                continue;
            }
            // 按条件过滤会话
            SysUserOnline online;
            if (StringUtils.isNotEmpty(ipaddr) && StringUtils.isNotEmpty(userName))
            {
                online = userOnlineService.selectOnlineByInfo(ipaddr, userName, user);
            }
            else if (StringUtils.isNotEmpty(ipaddr))
            {
                online = userOnlineService.selectOnlineByIpaddr(ipaddr, user);
            }
            else if (StringUtils.isNotEmpty(userName))
            {
                online = userOnlineService.selectOnlineByUserName(userName, user);
            }
            else
            {
                online = userOnlineService.loginUserToUserOnline(user);
            }
            if (Objects.nonNull(online))
            {
                sessionList.add(online);
            }
        }
        // 按用户ID聚合为在线用户列表
        List<SysOnlineUser> onlineUserList = userOnlineService.groupByUser(sessionList);
        return getDataTable(onlineUserList);
    }

    /**
     * 强退单个会话（Token）
     */
    @PreAuthorize("@ss.hasPermi('monitor:online:forceLogout')")
    @Log(title = "在线用户", businessType = BusinessType.FORCE)
    @DeleteMapping("/{tokenId}")
    public AjaxResult forceLogout(@PathVariable String tokenId)
    {
        redisCache.deleteObject(CacheConstants.LOGIN_TOKEN_KEY + tokenId);
        return success();
    }

    /**
     * 强退某个用户名下的全部在线会话（Token）
     */
    @PreAuthorize("@ss.hasPermi('monitor:online:forceLogout')")
    @Log(title = "在线用户", businessType = BusinessType.FORCE)
    @DeleteMapping("/user/{userId}")
    public AjaxResult forceLogoutByUser(@PathVariable Long userId)
    {
        Collection<String> keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
        for (String key : keys)
        {
            LoginUser user;
            try
            {
                // 兜底转换，兼容历史/异构序列化的会话，避免个别脏数据导致强退失败
                user = redisCache.getCacheObject(key, LoginUser.class);
            }
            catch (Exception e)
            {
                logger.error("在线会话解析失败，已跳过, key={}, err={}", key, e.getMessage());
                continue;
            }
            // 命中该用户ID的所有会话全部下线
            if (Objects.nonNull(user) && Objects.equals(userId, user.getUserId()))
            {
                redisCache.deleteObject(key);
            }
        }
        return success();
    }
}
