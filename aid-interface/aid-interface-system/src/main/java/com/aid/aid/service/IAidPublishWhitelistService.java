package com.aid.aid.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidPublishWhitelist;
import com.aid.aid.domain.vo.AidPublishUserVo;
import com.aid.aid.domain.vo.AidPublishWhitelistVo;

/**
 * 作品发布白名单Service接口
 *
 * @author 视觉AID
 */
public interface IAidPublishWhitelistService extends IService<AidPublishWhitelist>
{
    /**
     * 判断用户是否在发布白名单内
     *
     * @param userId 用户ID
     * @return 是否在白名单
     */
    boolean existsByUserId(Long userId);

    /**
     * 查询白名单列表（联表用户信息，keyword 匹配昵称/邮箱/手机号）
     *
     * @param keyword 搜索关键字（可空）
     * @return 白名单列表
     */
    List<AidPublishWhitelistVo> selectWhitelistVoList(String keyword);

    /**
     * 按 昵称/邮箱/手机号 搜索用户（带发布权限与白名单标记）
     *
     * @param keyword 搜索关键字
     * @return 用户列表
     */
    List<AidPublishUserVo> searchPublishUsers(String keyword);
}
