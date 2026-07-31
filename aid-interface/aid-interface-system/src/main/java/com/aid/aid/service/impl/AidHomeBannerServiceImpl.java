package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidHomeBanner;
import com.aid.aid.mapper.AidHomeBannerMapper;
import com.aid.aid.service.IAidHomeBannerService;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.SecurityUtils;

import cn.hutool.core.util.StrUtil;

/**
 * 首页 Banner 配置 Service 业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidHomeBannerServiceImpl extends ServiceImpl<AidHomeBannerMapper, AidHomeBanner> implements IAidHomeBannerService
{
    /**
     * 查询首页 Banner 配置
     *
     * @param id 首页 Banner 配置主键
     * @return 首页 Banner 配置
     */
    @Override
    public AidHomeBanner selectAidHomeBannerById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询首页 Banner 配置列表
     *
     * @param aidHomeBanner 首页 Banner 配置
     * @return 首页 Banner 配置
     */
    @Override
    public List<AidHomeBanner> selectAidHomeBannerList(AidHomeBanner aidHomeBanner)
    {
        LambdaQueryWrapper<AidHomeBanner> wrapper = Wrappers.lambdaQuery();
        if (aidHomeBanner != null)
        {
            // 标题模糊检索
            if (StrUtil.isNotBlank(aidHomeBanner.getTitle()))
            {
                wrapper.like(AidHomeBanner::getTitle, aidHomeBanner.getTitle());
            }
            // 资源类型精确检索
            if (StrUtil.isNotBlank(aidHomeBanner.getBannerType()))
            {
                wrapper.eq(AidHomeBanner::getBannerType, aidHomeBanner.getBannerType());
            }
            // 状态精确检索
            if (StrUtil.isNotBlank(aidHomeBanner.getStatus()))
            {
                wrapper.eq(AidHomeBanner::getStatus, aidHomeBanner.getStatus());
            }
        }
        // 排序：sort_order 升序，相同再按创建时间倒序
        wrapper.orderByAsc(AidHomeBanner::getSortOrder).orderByDesc(AidHomeBanner::getId);
        return this.list(wrapper);
    }

    /**
     * 新增首页 Banner 配置
     *
     * @param aidHomeBanner 首页 Banner 配置
     * @return 结果
     */
    @Override
    public int insertAidHomeBanner(AidHomeBanner aidHomeBanner)
    {
        // 填充创建时间与创建者
        aidHomeBanner.setCreateTime(DateUtils.getNowDate());
        aidHomeBanner.setCreateBy(SecurityUtils.getUsername());
        return this.save(aidHomeBanner) ? 1 : 0;
    }

    /**
     * 修改首页 Banner 配置
     *
     * @param aidHomeBanner 首页 Banner 配置
     * @return 结果
     */
    @Override
    public int updateAidHomeBanner(AidHomeBanner aidHomeBanner)
    {
        // 填充更新时间与更新者
        aidHomeBanner.setUpdateTime(DateUtils.getNowDate());
        aidHomeBanner.setUpdateBy(SecurityUtils.getUsername());
        return this.updateById(aidHomeBanner) ? 1 : 0;
    }

    /**
     * 批量删除首页 Banner 配置
     *
     * @param ids 需要删除的首页 Banner 配置主键
     * @return 结果
     */
    @Override
    public int deleteAidHomeBannerByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除首页 Banner 配置信息
     *
     * @param id 首页 Banner 配置主键
     * @return 结果
     */
    @Override
    public int deleteAidHomeBannerById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
