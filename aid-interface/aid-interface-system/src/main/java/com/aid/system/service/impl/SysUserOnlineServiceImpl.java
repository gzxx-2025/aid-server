package com.aid.system.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import cn.hutool.core.collection.CollectionUtil;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.utils.StringUtils;
import com.aid.system.domain.SysOnlineUser;
import com.aid.system.domain.SysUserOnline;
import com.aid.system.service.ISysUserOnlineService;

/**
 * 在线用户 服务层处理
 *
 * @author 视觉AID
 */
@Service
public class SysUserOnlineServiceImpl implements ISysUserOnlineService
{
    /**
     * 通过登录地址查询信息
     *
     * @param ipaddr 登录地址
     * @param user 用户信息
     * @return 在线用户信息
     */
    @Override
    public SysUserOnline selectOnlineByIpaddr(String ipaddr, LoginUser user)
    {
        if (StringUtils.equals(ipaddr, user.getIpaddr()))
        {
            return loginUserToUserOnline(user);
        }
        return null;
    }

    /**
     * 通过用户名称查询信息
     *
     * @param userName 用户名称
     * @param user 用户信息
     * @return 在线用户信息
     */
    @Override
    public SysUserOnline selectOnlineByUserName(String userName, LoginUser user)
    {
        if (StringUtils.equals(userName, user.getUsername()))
        {
            return loginUserToUserOnline(user);
        }
        return null;
    }

    /**
     * 通过登录地址/用户名称查询信息
     *
     * @param ipaddr 登录地址
     * @param userName 用户名称
     * @param user 用户信息
     * @return 在线用户信息
     */
    @Override
    public SysUserOnline selectOnlineByInfo(String ipaddr, String userName, LoginUser user)
    {
        if (StringUtils.equals(ipaddr, user.getIpaddr()) && StringUtils.equals(userName, user.getUsername()))
        {
            return loginUserToUserOnline(user);
        }
        return null;
    }

    /**
     * 设置在线用户信息
     *
     * @param user 用户信息
     * @return 在线用户
     */
    @Override
    public SysUserOnline loginUserToUserOnline(LoginUser user)
    {
        if (StringUtils.isNull(user) || StringUtils.isNull(user.getUser()))
        {
            return null;
        }
        SysUserOnline sysUserOnline = new SysUserOnline();
        sysUserOnline.setTokenId(user.getToken());
        sysUserOnline.setUserId(user.getUserId()); // 记录用户ID，用于按用户分组
        sysUserOnline.setUserName(user.getUsername());
        sysUserOnline.setIpaddr(user.getIpaddr());
        sysUserOnline.setLoginLocation(user.getLoginLocation());
        sysUserOnline.setBrowser(user.getBrowser());
        sysUserOnline.setOs(user.getOs());
        sysUserOnline.setLoginTime(user.getLoginTime());
        sysUserOnline.setExpireTime(user.getExpireTime()); // 会话过期时间
        if (StringUtils.isNotNull(user.getUser().getDept()))
        {
            sysUserOnline.setDeptName(user.getUser().getDept().getDeptName());
        }
        return sysUserOnline;
    }

    /**
     * 将扁平的在线会话列表按用户ID聚合为"在线用户"列表
     *
     * @param sessions 在线会话（Token）明细列表
     * @return 按用户聚合后的在线用户列表
     */
    @Override
    public List<SysOnlineUser> groupByUser(List<SysUserOnline> sessions)
    {
        List<SysOnlineUser> result = new ArrayList<>();
        if (CollectionUtil.isEmpty(sessions))
        {
            return result;
        }
        // 使用 LinkedHashMap 保证聚合后顺序稳定，key 为用户ID
        Map<Long, SysOnlineUser> userMap = new LinkedHashMap<>();
        for (SysUserOnline session : sessions)
        {
            if (Objects.isNull(session))
            {
                continue;
            }
            // 用户ID为空时用 -1 兜底，避免不同用户被错误合并到同一分组
            Long userId = Objects.isNull(session.getUserId()) ? -1L : session.getUserId();
            SysOnlineUser onlineUser = userMap.computeIfAbsent(userId, key -> {
                SysOnlineUser newUser = new SysOnlineUser();
                newUser.setUserId(session.getUserId());
                newUser.setUserName(session.getUserName());
                newUser.setDeptName(session.getDeptName());
                return newUser;
            });
            onlineUser.getTokens().add(session);
        }
        // 组装聚合字段：会话数、最近登录时间，并对内部会话按登录时间倒序
        for (SysOnlineUser onlineUser : userMap.values())
        {
            List<SysUserOnline> tokens = onlineUser.getTokens();
            // 会话按登录时间倒序，最近登录的在最前
            tokens.sort(Comparator.comparing(SysUserOnline::getLoginTime,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            onlineUser.setOnlineCount(tokens.size()); // 该用户在线会话数
            onlineUser.setLastLoginTime(tokens.get(0).getLoginTime()); // 最近一次登录时间
            result.add(onlineUser);
        }
        // 在线用户按最近登录时间倒序展示
        result.sort(Comparator.comparing(SysOnlineUser::getLastLoginTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }
}
