package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.mapper.AidRolePropSceneFormMapper;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import lombok.extern.slf4j.Slf4j;

/**
 * 角色道具场景形态(从)Service业务层处理
 *
 * @author 视觉AID
 */
@Service
@Slf4j
public class AidRolePropSceneFormServiceImpl extends ServiceImpl<AidRolePropSceneFormMapper, AidRolePropSceneForm> implements IAidRolePropSceneFormService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String DELETE_REASON_AUTO_OVERWRITE = "auto_overwrite";

    @Autowired
    private IAidRolePropSceneService assetService;

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
        requireEditableRoot(aidRolePropSceneForm == null ? null : aidRolePropSceneForm.getAssetId());
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
        if (aidRolePropSceneForm == null || aidRolePropSceneForm.getId() == null)
        {
            log.info("后台更新资产形态失败，形态ID为空");
            throw new ServiceException("形态不能为空");
        }
        AidRolePropSceneForm stored = this.getById(aidRolePropSceneForm.getId());
        if (stored == null)
        {
            return 0;
        }
        requireEditableRoot(stored.getAssetId());
        validateImmutableIdentity(stored, aidRolePropSceneForm);
        aidRolePropSceneForm.setUpdateTime(DateUtils.getNowDate());
        return this.update(aidRolePropSceneForm, Wrappers.<AidRolePropSceneForm>lambdaUpdate()
                .eq(AidRolePropSceneForm::getId, stored.getId())
                .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL)) ? 1 : 0;
    }

    private AidRolePropScene requireEditableRoot(Long assetId)
    {
        AidRolePropScene root = assetId == null ? null : assetService.getById(assetId);
        if (root == null || !Objects.equals(DEL_FLAG_NORMAL, root.getDelFlag()))
        {
            if (root != null && Objects.equals(DELETE_REASON_AUTO_OVERWRITE, root.getDeleteReason()))
            {
                log.info("后台更新自动覆盖资产形态被拒绝: assetId={}", assetId);
                throw new ServiceException("资产不可恢复");
            }
            log.info("后台更新资产形态失败，主资产无效: assetId={}", assetId);
            throw new ServiceException("资产已删除");
        }
        return root;
    }

    private void validateImmutableIdentity(AidRolePropSceneForm stored, AidRolePropSceneForm update)
    {
        boolean changed = update.getAssetId() != null
                && !Objects.equals(stored.getAssetId(), update.getAssetId());
        changed = changed || update.getProjectId() != null
                && !Objects.equals(stored.getProjectId(), update.getProjectId());
        changed = changed || update.getEpisodeId() != null
                && !Objects.equals(stored.getEpisodeId(), update.getEpisodeId());
        changed = changed || update.getUserId() != null
                && !Objects.equals(stored.getUserId(), update.getUserId());
        changed = changed || update.getCreateSource() != null
                && !Objects.equals(stored.getCreateSource(), update.getCreateSource());
        changed = changed || update.getDelFlag() != null
                && !Objects.equals(stored.getDelFlag(), update.getDelFlag());
        if (changed)
        {
            log.info("后台更新资产形态不可变身份被拒绝: formId={}", stored.getId());
            throw new ServiceException("形态归属不可改");
        }
        if (!Objects.equals(DEL_FLAG_NORMAL, stored.getDelFlag()))
        {
            log.info("后台更新已删除资产形态被拒绝: formId={}", stored.getId());
            throw new ServiceException("形态已删除");
        }
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
