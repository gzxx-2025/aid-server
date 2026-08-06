package com.aid.user.service;

import com.aid.user.dto.AdminUserCreateRequest;
import com.aid.user.vo.AdminUserCreateVO;

/**
 * 后台 C 端用户创建服务。
 *
 * @author 视觉AID
 */
public interface IAdminUserCreateService {

    /**
     * 创建 C 端用户并初始化账户扩展信息。
     *
     * @param request 创建请求
     * @param operator 管理员账号
     * @return 创建结果
     */
    AdminUserCreateVO createUser(AdminUserCreateRequest request, String operator);
}
