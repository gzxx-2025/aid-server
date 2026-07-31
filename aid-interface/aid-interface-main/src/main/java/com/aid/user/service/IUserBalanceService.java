package com.aid.user.service;

import com.aid.user.vo.UserBalanceVO;

/**
 * C 端用户账户积分 Service。
 *
 * @author 视觉AID
 */
public interface IUserBalanceService {

    /**
     * 查询当前用户账户积分。
     *
     * @param userId 当前登录用户 ID
     * @return 账户积分信息
     */
    UserBalanceVO getBalance(Long userId);
}
