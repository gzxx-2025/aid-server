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
import com.aid.aid.mapper.AidRolePropSceneFormMapper;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.service.IAidRolePropSceneFormService;

/**
 * 角色道具场景形态(从)Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidRolePropSceneFormServiceImpl extends ServiceImpl<AidRolePropSceneFormMapper, AidRolePropSceneForm> implements IAidRolePropSceneFormService
{
    @Autowired
    private AidRolePropSceneFormMapper aidRolePropSceneFormMapper;

    /**
     * 查询角色道具场景形态(从)
     *
     * @param id 角色道具场景形态(从)主键
     * @return 角色道具场景形态(从)
     */
    @Override
    public AidRolePropSceneForm selectAidRolePropSceneFormById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询角色道具场景形态(从)列表
     *
     * @param aidRolePropSceneForm 角色道具场景形态(从)
     * @return 角色道具场景形态(从)
     */
    @Override
    public List<AidRolePropSceneForm> selectAidRolePropSceneFormList(AidRolePropSceneForm aidRolePropSceneForm)
    {
        LambdaQueryWrapper<AidRolePropSceneForm> wrapper = Wrappers.lambdaQuery();
        if (aidRolePropSceneForm != null)
        {
            if (aidRolePropSceneForm.getAssetId() != null)
            {
                wrapper.eq(AidRolePropSceneForm::getAssetId, aidRolePropSceneForm.getAssetId());
            }
            if (aidRolePropSceneForm.getProjectId() != null)
            {
                wrapper.eq(AidRolePropSceneForm::getProjectId, aidRolePropSceneForm.getProjectId());
            }
            if (aidRolePropSceneForm.getEpisodeId() != null)
            {
                wrapper.eq(AidRolePropSceneForm::getEpisodeId, aidRolePropSceneForm.getEpisodeId());
            }
            if (aidRolePropSceneForm.getUserId() != null)
            {
                wrapper.eq(AidRolePropSceneForm::getUserId, aidRolePropSceneForm.getUserId());
            }
            if (StrUtil.isNotBlank(aidRolePropSceneForm.getName()))
            {
                wrapper.like(AidRolePropSceneForm::getName, aidRolePropSceneForm.getName());
            }
            if (StrUtil.isNotBlank(aidRolePropSceneForm.getVisualDescStatus()))
            {
                wrapper.eq(AidRolePropSceneForm::getVisualDescStatus, aidRolePropSceneForm.getVisualDescStatus());
            }
            if (StrUtil.isNotBlank(aidRolePropSceneForm.getCreateSource()))
            {
                wrapper.eq(AidRolePropSceneForm::getCreateSource, aidRolePropSceneForm.getCreateSource());
            }
        }
        wrapper.orderByDesc(AidRolePropSceneForm::getId);
        return this.list(wrapper);
    }

    /**
     * 新增角色道具场景形态(从)
     *
     * @param aidRolePropSceneForm 角色道具场景形态(从)
     * @return 结果
     */
    @Override
    public int insertAidRolePropSceneForm(AidRolePropSceneForm aidRolePropSceneForm)
    {
        aidRolePropSceneForm.setCreateTime(DateUtils.getNowDate());
        return this.save(aidRolePropSceneForm) ? 1 : 0;
    }

    /**
     * 修改角色道具场景形态(从)
     *
     * @param aidRolePropSceneForm 角色道具场景形态(从)
     * @return 结果
     */
    @Override
    public int updateAidRolePropSceneForm(AidRolePropSceneForm aidRolePropSceneForm)
    {
        aidRolePropSceneForm.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidRolePropSceneForm) ? 1 : 0;
    }

    /**
     * 批量删除角色道具场景形态(从)
     *
     * @param ids 需要删除的角色道具场景形态(从)主键
     * @return 结果
     */
    @Override
    public int deleteAidRolePropSceneFormByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除角色道具场景形态(从)信息
     *
     * @param id 角色道具场景形态(从)主键
     * @return 结果
     */
    @Override
    public int deleteAidRolePropSceneFormById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
