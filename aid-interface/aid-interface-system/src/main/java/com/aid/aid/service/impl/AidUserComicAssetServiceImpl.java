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
import com.aid.aid.mapper.AidUserComicAssetMapper;
import com.aid.aid.domain.AidUserComicAsset;
import com.aid.aid.service.IAidUserComicAssetService;

/**
 * 用户自定义漫画参考资产Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidUserComicAssetServiceImpl extends ServiceImpl<AidUserComicAssetMapper, AidUserComicAsset> implements IAidUserComicAssetService
{
    @Autowired
    private AidUserComicAssetMapper aidUserComicAssetMapper;

    /**
     * 查询用户自定义漫画参考资产
     *
     * @param id 用户自定义漫画参考资产主键
     * @return 用户自定义漫画参考资产
     */
    @Override
    public AidUserComicAsset selectAidUserComicAssetById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询用户自定义漫画参考资产列表
     *
     * @param aidUserComicAsset 用户自定义漫画参考资产
     * @return 用户自定义漫画参考资产
     */
    @Override
    public List<AidUserComicAsset> selectAidUserComicAssetList(AidUserComicAsset aidUserComicAsset)
    {
        LambdaQueryWrapper<AidUserComicAsset> wrapper = Wrappers.lambdaQuery();
        if (aidUserComicAsset != null)
        {
            if (aidUserComicAsset.getUserId() != null)
            {
                wrapper.eq(AidUserComicAsset::getUserId, aidUserComicAsset.getUserId());
            }
            if (StrUtil.isNotBlank(aidUserComicAsset.getAssetType()))
            {
                wrapper.eq(AidUserComicAsset::getAssetType, aidUserComicAsset.getAssetType());
            }
            if (StrUtil.isNotBlank(aidUserComicAsset.getAssetName()))
            {
                wrapper.like(AidUserComicAsset::getAssetName, aidUserComicAsset.getAssetName());
            }
            if (StrUtil.isNotBlank(aidUserComicAsset.getSourceType()))
            {
                wrapper.eq(AidUserComicAsset::getSourceType, aidUserComicAsset.getSourceType());
            }
            if (StrUtil.isNotBlank(aidUserComicAsset.getStatus()))
            {
                wrapper.eq(AidUserComicAsset::getStatus, aidUserComicAsset.getStatus());
            }
        }
        wrapper.orderByAsc(AidUserComicAsset::getSortOrder).orderByDesc(AidUserComicAsset::getId);
        return this.list(wrapper);
    }

    /**
     * 新增用户自定义漫画参考资产
     *
     * @param aidUserComicAsset 用户自定义漫画参考资产
     * @return 结果
     */
    @Override
    public int insertAidUserComicAsset(AidUserComicAsset aidUserComicAsset)
    {
        aidUserComicAsset.setCreateTime(DateUtils.getNowDate());
        return this.save(aidUserComicAsset) ? 1 : 0;
    }

    /**
     * 修改用户自定义漫画参考资产
     *
     * @param aidUserComicAsset 用户自定义漫画参考资产
     * @return 结果
     */
    @Override
    public int updateAidUserComicAsset(AidUserComicAsset aidUserComicAsset)
    {
        aidUserComicAsset.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidUserComicAsset) ? 1 : 0;
    }

    /**
     * 批量删除用户自定义漫画参考资产
     *
     * @param ids 需要删除的用户自定义漫画参考资产主键
     * @return 结果
     */
    @Override
    public int deleteAidUserComicAssetByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除用户自定义漫画参考资产信息
     *
     * @param id 用户自定义漫画参考资产主键
     * @return 结果
     */
    @Override
    public int deleteAidUserComicAssetById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
