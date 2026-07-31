package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidGenAgentPool;
import com.aid.aid.mapper.AidGenAgentPoolMapper;
import com.aid.aid.service.IAidGenAgentPoolService;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.SecurityUtils;

/**
 * 生成链路智能体可选池Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidGenAgentPoolServiceImpl extends ServiceImpl<AidGenAgentPoolMapper, AidGenAgentPool>
        implements IAidGenAgentPoolService
{
    /** 删除标志：正常（未删除） */
    private static final String DEL_FLAG_NORMAL = "0";

    @Override
    public AidGenAgentPool selectAidGenAgentPoolById(Long id)
    {
        return this.getById(id);
    }

    @Override
    public List<AidGenAgentPool> selectAidGenAgentPoolList(AidGenAgentPool query)
    {
        LambdaQueryWrapper<AidGenAgentPool> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidGenAgentPool::getDelFlag, DEL_FLAG_NORMAL);
        if (query != null)
        {
            if (StrUtil.isNotBlank(query.getStep()))
            {
                wrapper.eq(AidGenAgentPool::getStep, query.getStep());
            }
            if (StrUtil.isNotBlank(query.getBizCategoryCode()))
            {
                wrapper.eq(AidGenAgentPool::getBizCategoryCode, query.getBizCategoryCode());
            }
            if (StrUtil.isNotBlank(query.getCreationMode()))
            {
                wrapper.eq(AidGenAgentPool::getCreationMode, query.getCreationMode());
            }
            if (StrUtil.isNotBlank(query.getScriptType()))
            {
                wrapper.eq(AidGenAgentPool::getScriptType, query.getScriptType());
            }
            if (StrUtil.isNotBlank(query.getStrategy()))
            {
                wrapper.eq(AidGenAgentPool::getStrategy, query.getStrategy());
            }
            if (StrUtil.isNotBlank(query.getAgentCode()))
            {
                wrapper.like(AidGenAgentPool::getAgentCode, query.getAgentCode());
            }
            if (StrUtil.isNotBlank(query.getStatus()))
            {
                wrapper.eq(AidGenAgentPool::getStatus, query.getStatus());
            }
        }
        wrapper.orderByAsc(AidGenAgentPool::getStep)
                .orderByAsc(AidGenAgentPool::getSortOrder)
                .orderByAsc(AidGenAgentPool::getId);
        return this.list(wrapper);
    }

    @Override
    public int insertAidGenAgentPool(AidGenAgentPool entity)
    {
        entity.setDelFlag(DEL_FLAG_NORMAL);
        entity.setCreateBy(currentUser());
        entity.setCreateTime(DateUtils.getNowDate());
        return this.save(entity) ? 1 : 0;
    }

    @Override
    public int updateAidGenAgentPool(AidGenAgentPool entity)
    {
        entity.setUpdateBy(currentUser());
        entity.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(entity) ? 1 : 0;
    }

    @Override
    public int deleteAidGenAgentPoolByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /** 当前操作者用户名，取不到回退 system */
    private String currentUser()
    {
        try
        {
            String name = SecurityUtils.getUsername();
            return StrUtil.isBlank(name) ? "system" : name;
        }
        catch (Exception e)
        {
            return "system";
        }
    }
}
