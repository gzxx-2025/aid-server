package com.aid.aid.service.impl;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidPublishWhitelist;
import com.aid.aid.domain.vo.AidPublishUserVo;
import com.aid.aid.domain.vo.AidPublishWhitelistVo;
import com.aid.aid.mapper.AidPublishWhitelistMapper;
import com.aid.aid.service.IAidPublishWhitelistService;

/**
 * 作品发布白名单Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidPublishWhitelistServiceImpl extends ServiceImpl<AidPublishWhitelistMapper, AidPublishWhitelist>
        implements IAidPublishWhitelistService
{
    /**
     * 判断用户是否在发布白名单内
     * 查询字段精简：仅 count 存在性判断
     *
     * @param userId 用户ID
     * @return 是否在白名单
     */
    @Override
    public boolean existsByUserId(Long userId)
    {
        if (Objects.isNull(userId))
        {
            return false;
        }
        return this.count(Wrappers.<AidPublishWhitelist>lambdaQuery()
                .eq(AidPublishWhitelist::getUserId, userId)) > 0;
    }

    /**
     * 查询白名单列表（联表用户信息）
     *
     * @param keyword 搜索关键字（可空，匹配昵称/邮箱/手机号）
     * @return 白名单列表
     */
    @Override
    public List<AidPublishWhitelistVo> selectWhitelistVoList(String keyword)
    {
        return this.baseMapper.selectWhitelistVoList(keyword);
    }

    /**
     * 按 昵称/邮箱/手机号 搜索用户
     *
     * @param keyword 搜索关键字
     * @return 用户列表（带发布权限与白名单标记）
     */
    @Override
    public List<AidPublishUserVo> searchPublishUsers(String keyword)
    {
        return this.baseMapper.searchPublishUsers(keyword);
    }
}
