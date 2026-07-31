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
import com.aid.aid.mapper.AidRolePropSceneMapper;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.service.IAidRolePropSceneService;

/**
 * 角色道具场景Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidRolePropSceneServiceImpl extends ServiceImpl<AidRolePropSceneMapper, AidRolePropScene> implements IAidRolePropSceneService
{
    @Autowired
    private AidRolePropSceneMapper aidRolePropSceneMapper;

    /**
     * 查询角色道具场景
     *
     * @param id 角色道具场景主键
     * @return 角色道具场景
     */
    @Override
    public AidRolePropScene selectAidRolePropSceneById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询角色道具场景列表
     *
     * @param aidRolePropScene 角色道具场景
     * @return 角色道具场景
     */
    @Override
    public List<AidRolePropScene> selectAidRolePropSceneList(AidRolePropScene aidRolePropScene)
    {
        LambdaQueryWrapper<AidRolePropScene> wrapper = Wrappers.lambdaQuery();
        if (aidRolePropScene != null)
        {
            if (aidRolePropScene.getProjectId() != null)
            {
                wrapper.eq(AidRolePropScene::getProjectId, aidRolePropScene.getProjectId());
            }
            if (aidRolePropScene.getEpisodeId() != null)
            {
                wrapper.eq(AidRolePropScene::getEpisodeId, aidRolePropScene.getEpisodeId());
            }
            if (aidRolePropScene.getUserId() != null)
            {
                wrapper.eq(AidRolePropScene::getUserId, aidRolePropScene.getUserId());
            }
            if (StrUtil.isNotBlank(aidRolePropScene.getName()))
            {
                wrapper.like(AidRolePropScene::getName, aidRolePropScene.getName());
            }
            if (StrUtil.isNotBlank(aidRolePropScene.getAssetType()))
            {
                wrapper.eq(AidRolePropScene::getAssetType, aidRolePropScene.getAssetType());
            }
            if (StrUtil.isNotBlank(aidRolePropScene.getGender()))
            {
                wrapper.eq(AidRolePropScene::getGender, aidRolePropScene.getGender());
            }
            if (StrUtil.isNotBlank(aidRolePropScene.getAgeRange()))
            {
                wrapper.eq(AidRolePropScene::getAgeRange, aidRolePropScene.getAgeRange());
            }
            if (StrUtil.isNotBlank(aidRolePropScene.getRoleLevel()))
            {
                wrapper.eq(AidRolePropScene::getRoleLevel, aidRolePropScene.getRoleLevel());
            }
            if (StrUtil.isNotBlank(aidRolePropScene.getCreateSource()))
            {
                wrapper.eq(AidRolePropScene::getCreateSource, aidRolePropScene.getCreateSource());
            }
            if (aidRolePropScene.getHasCrowd() != null)
            {
                wrapper.eq(AidRolePropScene::getHasCrowd, aidRolePropScene.getHasCrowd());
            }
        }
        wrapper.orderByAsc(AidRolePropScene::getFirstSceneCode).orderByDesc(AidRolePropScene::getId);
        return this.list(wrapper);
    }

    /**
     * 新增角色道具场景
     *
     * @param aidRolePropScene 角色道具场景
     * @return 结果
     */
    @Override
    public int insertAidRolePropScene(AidRolePropScene aidRolePropScene)
    {
        aidRolePropScene.setCreateTime(DateUtils.getNowDate());
        return this.save(aidRolePropScene) ? 1 : 0;
    }

    /**
     * 修改角色道具场景
     *
     * @param aidRolePropScene 角色道具场景
     * @return 结果
     */
    @Override
    public int updateAidRolePropScene(AidRolePropScene aidRolePropScene)
    {
        aidRolePropScene.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidRolePropScene) ? 1 : 0;
    }

    /**
     * 批量删除角色道具场景
     *
     * @param ids 需要删除的角色道具场景主键
     * @return 结果
     */
    @Override
    public int deleteAidRolePropSceneByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除角色道具场景信息
     *
     * @param id 角色道具场景主键
     * @return 结果
     */
    @Override
    public int deleteAidRolePropSceneById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
