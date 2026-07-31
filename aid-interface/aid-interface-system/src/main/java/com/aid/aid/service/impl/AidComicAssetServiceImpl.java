package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.mapper.AidComicAssetMapper;
import com.aid.aid.domain.AidComicAsset;
import com.aid.aid.service.IAidComicAssetService;

/**
 * 项目提取资产Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidComicAssetServiceImpl extends ServiceImpl<AidComicAssetMapper, AidComicAsset> implements IAidComicAssetService
{
    @Autowired
    private AidComicAssetMapper aidComicAssetMapper;

    /**
     * 查询项目提取资产
     *
     * @param id 项目提取资产主键
     * @return 项目提取资产
     */
    @Override
    public AidComicAsset selectAidComicAssetById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询项目提取资产列表
     *
     * @param aidComicAsset 项目提取资产
     * @return 项目提取资产
     */
    @Override
    public List<AidComicAsset> selectAidComicAssetList(AidComicAsset aidComicAsset)
    {
        LambdaQueryWrapper<AidComicAsset> lambdaQueryWrapper = Wrappers.lambdaQuery();
        // 动态查询条件
        if (StringUtils.isNotEmpty(aidComicAsset.getAssetType())) {
            lambdaQueryWrapper.eq(AidComicAsset::getAssetType, aidComicAsset.getAssetType());
        }
        if (StringUtils.isNotEmpty(aidComicAsset.getAssetName())) {
            lambdaQueryWrapper.like(AidComicAsset::getAssetName, aidComicAsset.getAssetName());
        }
        // 按创建时间倒序
        lambdaQueryWrapper.orderByDesc(AidComicAsset::getCreateTime);
        return this.list(lambdaQueryWrapper);
    }

    /**
     * 新增项目提取资产
     *
     * @param aidComicAsset 项目提取资产
     * @return 结果
     */
    @Override
    public int insertAidComicAsset(AidComicAsset aidComicAsset)
    {
        aidComicAsset.setCreateTime(DateUtils.getNowDate());
        return this.save(aidComicAsset) ? 1 : 0;
    }

    /**
     * 修改项目提取资产
     *
     * @param aidComicAsset 项目提取资产
     * @return 结果
     */
    @Override
    public int updateAidComicAsset(AidComicAsset aidComicAsset)
    {
        aidComicAsset.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidComicAsset) ? 1 : 0;
    }

    /**
     * 批量删除项目提取资产
     *
     * @param ids 需要删除的项目提取资产主键
     * @return 结果
     */
    @Override
    public int deleteAidComicAssetByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除项目提取资产信息
     *
     * @param id 项目提取资产主键
     * @return 结果
     */
    @Override
    public int deleteAidComicAssetById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
