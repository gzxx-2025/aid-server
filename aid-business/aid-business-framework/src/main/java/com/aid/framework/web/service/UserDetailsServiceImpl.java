package com.aid.framework.web.service;

import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.enums.UserStatus;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.MessageUtils;
import com.aid.common.utils.StringUtils;
import com.aid.core.service.ISysUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 用户验证处理
 *
 * @author AID
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService
{
    private static final Logger log = LoggerFactory.getLogger(UserDetailsServiceImpl.class);

    @Autowired
    private ISysUserService userService;
    
    @Autowired
    private SysPasswordService passwordService;

    @Autowired
    private SysPermissionService permissionService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException
    {
        SysUser user = resolveUserByAccount(username);
        if (StringUtils.isNull(user))
        {
            log.info("登录用户：{} 不存在.", username);
            throw new ServiceException(MessageUtils.message("user.not.exists"));
        }
        else if (UserStatus.DELETED.getCode().equals(user.getDelFlag()))
        {
            log.info("登录用户：{} 已被删除.", username);
            throw new ServiceException(MessageUtils.message("user.password.delete"));
        }
        else if (UserStatus.DISABLE.getCode().equals(user.getStatus()))
        {
            log.info("登录用户：{} 已被停用.", username);
            throw new ServiceException(MessageUtils.message("user.blocked"));
        }

        passwordService.validate(user);

        return createLoginUser(user);
    }

    /**
     * 根据账号动态解析用户
     * 手机号 → 先查phone，未命中再查userName
     * 邮箱   → 查email
     * 其他   → 查userName
     *
     * @param account 用户输入的账号
     * @return 用户信息
     */
    private SysUser resolveUserByAccount(String account)
    {
        // 判断是否为手机号（中国大陆手机号）
        if (account.matches("^1[3-9]\\d{9}$"))
        {
            SysUser user = userService.selectUserByPhonenumber(account);
            if (StringUtils.isNotNull(user))
            {
                return user;
            }
            // 手机号未匹配，回退到userName查询
            log.info("手机号 {} 未匹配到用户，尝试userName查询", account);
            return userService.selectUserByUserName(account);
        }

        // 判断是否为邮箱
        if (account.matches("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"))
        {
            return userService.selectUserByEmail(account);
        }

        // 默认按userName查询
        return userService.selectUserByUserName(account);
    }

    public UserDetails createLoginUser(SysUser user)
    {
        return new LoginUser(user.getUserId(), user.getDeptId(), user, permissionService.getMenuPermission(user));
    }
}
