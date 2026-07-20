package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import cn.hutool.core.util.StrUtil;
import com.aid.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.mapper.AidUserProfileMapper;
import com.aid.aid.domain.AidUserProfile;
import com.aid.aid.domain.vo.AidUserProfileVo;
import com.aid.aid.service.IAidUserProfileService;

/**
 * 用户扩展信息Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidUserProfileServiceImpl extends ServiceImpl<AidUserProfileMapper, AidUserProfile> implements IAidUserProfileService
{
    @Autowired
    private AidUserProfileMapper aidUserProfileMapper;

    /**
     * 查询用户扩展信息
     *
     * @param id 用户扩展信息主键
     * @return 用户扩展信息
     */
    @Override
    public AidUserProfile selectAidUserProfileById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询用户扩展信息列表
     *
     * @param aidUserProfile 用户扩展信息
     * @return 用户扩展信息
     */
    @Override
    public List<AidUserProfile> selectAidUserProfileList(AidUserProfile aidUserProfile)
    {
        LambdaQueryWrapper<AidUserProfile> wrapper = Wrappers.lambdaQuery();
        if (aidUserProfile != null)
        {
            if (aidUserProfile.getUserId() != null)
            {
                wrapper.eq(AidUserProfile::getUserId, aidUserProfile.getUserId());
            }
            if (StrUtil.isNotBlank(aidUserProfile.getIsReal()))
            {
                wrapper.eq(AidUserProfile::getIsReal, aidUserProfile.getIsReal());
            }
            if (StrUtil.isNotBlank(aidUserProfile.getRealName()))
            {
                wrapper.like(AidUserProfile::getRealName, aidUserProfile.getRealName());
            }
            if (StrUtil.isNotBlank(aidUserProfile.getMemberLevel()))
            {
                wrapper.eq(AidUserProfile::getMemberLevel, aidUserProfile.getMemberLevel());
            }
        }
        wrapper.orderByDesc(AidUserProfile::getId);
        return this.list(wrapper);
    }

    /**
     * 新增用户扩展信息
     *
     * @param aidUserProfile 用户扩展信息
     * @return 结果
     */
    @Override
    public int insertAidUserProfile(AidUserProfile aidUserProfile)
    {
        aidUserProfile.setCreateTime(DateUtils.getNowDate());
        return this.save(aidUserProfile) ? 1 : 0;
    }

    /**
     * 修改用户扩展信息
     *
     * @param aidUserProfile 用户扩展信息
     * @return 结果
     */
    @Override
    public int updateAidUserProfile(AidUserProfile aidUserProfile)
    {
        aidUserProfile.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidUserProfile) ? 1 : 0;
    }

    /**
     * 批量删除用户扩展信息
     *
     * @param ids 需要删除的用户扩展信息主键
     * @return 结果
     */
    @Override
    public int deleteAidUserProfileByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除用户扩展信息信息
     *
     * @param id 用户扩展信息主键
     * @return 结果
     */
    @Override
    public int deleteAidUserProfileById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }

    /**
     * 按 userId 查询用户扩展信息
     */
    @Override
    public AidUserProfile getByUserId(Long userId)
    {
        if (userId == null)
        {
            return null;
        }
        LambdaQueryWrapper<AidUserProfile> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidUserProfile::getUserId, userId).last("limit 1");
        return this.getOne(wrapper);
    }

    /**
     * 关联 sys_user 查询用户聚合信息列表（后台用户管理）
     */
    @Override
    public List<AidUserProfileVo> selectUserProfileVoList(AidUserProfileVo query)
    {
        // 联表查询昵称/手机号/状态等基础信息，分页由 Controller 的 startPage 统一处理
        return aidUserProfileMapper.selectUserProfileVoList(query);
    }
}
