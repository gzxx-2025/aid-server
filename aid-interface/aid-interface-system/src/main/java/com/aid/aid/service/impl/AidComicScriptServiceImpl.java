package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import com.aid.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.mapper.AidComicScriptMapper;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.service.IAidComicScriptService;

/**
 * 剧本原文与简化版Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidComicScriptServiceImpl extends ServiceImpl<AidComicScriptMapper, AidComicScript> implements IAidComicScriptService
{
    @Autowired
    private AidComicScriptMapper aidComicScriptMapper;

    /**
     * 查询剧本原文与简化版
     *
     * @param id 剧本原文与简化版主键
     * @return 剧本原文与简化版
     */
    @Override
    public AidComicScript selectAidComicScriptById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询剧本原文与简化版列表
     *
     * @param aidComicScript 剧本原文与简化版
     * @return 剧本原文与简化版
     */
    @Override
    public List<AidComicScript> selectAidComicScriptList(AidComicScript aidComicScript)
    {
        LambdaQueryWrapper<AidComicScript> wrapper = Wrappers.lambdaQuery();
        if (aidComicScript != null)
        {
            if (aidComicScript.getProjectId() != null)
            {
                wrapper.eq(AidComicScript::getProjectId, aidComicScript.getProjectId());
            }
            if (aidComicScript.getEpisodeId() != null)
            {
                wrapper.eq(AidComicScript::getEpisodeId, aidComicScript.getEpisodeId());
            }
            if (aidComicScript.getUserId() != null)
            {
                wrapper.eq(AidComicScript::getUserId, aidComicScript.getUserId());
            }
            if (aidComicScript.getIsExtracted() != null)
            {
                wrapper.eq(AidComicScript::getIsExtracted, aidComicScript.getIsExtracted());
            }
            if (aidComicScript.getComicVersion() != null)
            {
                wrapper.eq(AidComicScript::getComicVersion, aidComicScript.getComicVersion());
            }
            if (aidComicScript.getStatus() != null)
            {
                wrapper.eq(AidComicScript::getStatus, aidComicScript.getStatus());
            }
        }
        wrapper.orderByDesc(AidComicScript::getId);
        return this.list(wrapper);
    }

    /**
     * 新增剧本原文与简化版
     *
     * @param aidComicScript 剧本原文与简化版
     * @return 结果
     */
    @Override
    public int insertAidComicScript(AidComicScript aidComicScript)
    {
        aidComicScript.setCreateTime(DateUtils.getNowDate());
        return this.save(aidComicScript) ? 1 : 0;
    }

    /**
     * 修改剧本原文与简化版
     *
     * @param aidComicScript 剧本原文与简化版
     * @return 结果
     */
    @Override
    public int updateAidComicScript(AidComicScript aidComicScript)
    {
        aidComicScript.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidComicScript) ? 1 : 0;
    }

    /**
     * 批量删除剧本原文与简化版
     *
     * @param ids 需要删除的剧本原文与简化版主键
     * @return 结果
     */
    @Override
    public int deleteAidComicScriptByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除剧本原文与简化版信息
     *
     * @param id 剧本原文与简化版主键
     * @return 结果
     */
    @Override
    public int deleteAidComicScriptById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
