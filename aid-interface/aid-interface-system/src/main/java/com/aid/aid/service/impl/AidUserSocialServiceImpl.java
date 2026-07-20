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
import com.aid.aid.mapper.AidUserSocialMapper;
import com.aid.aid.domain.AidUserSocial;
import com.aid.aid.service.IAidUserSocialService;

/**
 * 用户第三方登录授权Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidUserSocialServiceImpl extends ServiceImpl<AidUserSocialMapper, AidUserSocial> implements IAidUserSocialService
{
    @Autowired
    private AidUserSocialMapper aidUserSocialMapper;

    /**
     * 查询用户第三方登录授权
     *
     * @param id 用户第三方登录授权主键
     * @return 用户第三方登录授权
     */
    @Override
    public AidUserSocial selectAidUserSocialById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询用户第三方登录授权列表
     *
     * @param aidUserSocial 用户第三方登录授权
     * @return 用户第三方登录授权
     */
    @Override
    public List<AidUserSocial> selectAidUserSocialList(AidUserSocial aidUserSocial)
    {
        LambdaQueryWrapper<AidUserSocial> wrapper = Wrappers.lambdaQuery();
        if (aidUserSocial != null)
        {
            if (aidUserSocial.getUserId() != null)
            {
                wrapper.eq(AidUserSocial::getUserId, aidUserSocial.getUserId());
            }
            if (StrUtil.isNotBlank(aidUserSocial.getPlatformSource()))
            {
                wrapper.eq(AidUserSocial::getPlatformSource, aidUserSocial.getPlatformSource());
            }
            if (StrUtil.isNotBlank(aidUserSocial.getOpenid()))
            {
                wrapper.eq(AidUserSocial::getOpenid, aidUserSocial.getOpenid());
            }
            if (StrUtil.isNotBlank(aidUserSocial.getUnionid()))
            {
                wrapper.eq(AidUserSocial::getUnionid, aidUserSocial.getUnionid());
            }
        }
        wrapper.orderByDesc(AidUserSocial::getId);
        return this.list(wrapper);
    }

    /**
     * 新增用户第三方登录授权
     *
     * @param aidUserSocial 用户第三方登录授权
     * @return 结果
     */
    @Override
    public int insertAidUserSocial(AidUserSocial aidUserSocial)
    {
        aidUserSocial.setCreateTime(DateUtils.getNowDate());
        return this.save(aidUserSocial) ? 1 : 0;
    }

    /**
     * 修改用户第三方登录授权
     *
     * @param aidUserSocial 用户第三方登录授权
     * @return 结果
     */
    @Override
    public int updateAidUserSocial(AidUserSocial aidUserSocial)
    {
        aidUserSocial.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidUserSocial) ? 1 : 0;
    }

    /**
     * 批量删除用户第三方登录授权
     *
     * @param ids 需要删除的用户第三方登录授权主键
     * @return 结果
     */
    @Override
    public int deleteAidUserSocialByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除用户第三方登录授权信息
     *
     * @param id 用户第三方登录授权主键
     * @return 结果
     */
    @Override
    public int deleteAidUserSocialById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
