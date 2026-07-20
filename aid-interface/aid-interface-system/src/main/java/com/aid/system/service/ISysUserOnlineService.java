package com.aid.system.service;

import java.util.List;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.system.domain.SysOnlineUser;
import com.aid.system.domain.SysUserOnline;

/**
 * 在线用户 服务层
 *
 * @author 视觉AID
 */
public interface ISysUserOnlineService
{
    /**
     * 通过登录地址查询信息
     *
     * @param ipaddr 登录地址
     * @param user 用户信息
     * @return 在线用户信息
     */
    public SysUserOnline selectOnlineByIpaddr(String ipaddr, LoginUser user);

    /**
     * 通过用户名称查询信息
     *
     * @param userName 用户名称
     * @param user 用户信息
     * @return 在线用户信息
     */
    public SysUserOnline selectOnlineByUserName(String userName, LoginUser user);

    /**
     * 通过登录地址/用户名称查询信息
     *
     * @param ipaddr 登录地址
     * @param userName 用户名称
     * @param user 用户信息
     * @return 在线用户信息
     */
    public SysUserOnline selectOnlineByInfo(String ipaddr, String userName, LoginUser user);

    /**
     * 设置在线用户信息
     *
     * @param user 用户信息
     * @return 在线用户
     */
    public SysUserOnline loginUserToUserOnline(LoginUser user);

    /**
     * 将扁平的在线会话列表按用户ID聚合为"在线用户"列表
     * 一个用户可能持有多个未过期的Token，聚合后同一用户只显示一行，明细挂在tokens下
     *
     * @param sessions 在线会话（Token）明细列表
     * @return 按用户聚合后的在线用户列表
     */
    public List<SysOnlineUser> groupByUser(List<SysUserOnline> sessions);
}
