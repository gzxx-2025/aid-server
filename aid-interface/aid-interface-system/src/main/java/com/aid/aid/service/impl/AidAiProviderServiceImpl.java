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
import com.aid.aid.mapper.AidAiProviderMapper;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.IAidAiProviderService;

/**
 * AI大模型服务商(官方渠道)配置Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidAiProviderServiceImpl extends ServiceImpl<AidAiProviderMapper, AidAiProvider> implements IAidAiProviderService
{
    @Autowired
    private AidAiProviderMapper aidAiProviderMapper;

    /**
     * 查询AI大模型服务商(官方渠道)配置
     *
     * @param id AI大模型服务商(官方渠道)配置主键
     * @return AI大模型服务商(官方渠道)配置
     */
    @Override
    public AidAiProvider selectAidAiProviderById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询AI大模型服务商(官方渠道)配置列表
     *
     * @param aidAiProvider AI大模型服务商(官方渠道)配置
     * @return AI大模型服务商(官方渠道)配置
     */
    @Override
    public List<AidAiProvider> selectAidAiProviderList(AidAiProvider aidAiProvider)
    {
        LambdaQueryWrapper<AidAiProvider> wrapper = Wrappers.lambdaQuery();
        if (aidAiProvider != null)
        {
            if (StrUtil.isNotBlank(aidAiProvider.getProviderName()))
            {
                wrapper.like(AidAiProvider::getProviderName, aidAiProvider.getProviderName());
            }
            if (StrUtil.isNotBlank(aidAiProvider.getProviderCode()))
            {
                wrapper.like(AidAiProvider::getProviderCode, aidAiProvider.getProviderCode());
            }
            if (StrUtil.isNotBlank(aidAiProvider.getStatus()))
            {
                wrapper.eq(AidAiProvider::getStatus, aidAiProvider.getStatus());
            }
        }
        wrapper.orderByDesc(AidAiProvider::getId);
        return this.list(wrapper);
    }

    /**
     * 新增AI大模型服务商(官方渠道)配置
     *
     * @param aidAiProvider AI大模型服务商(官方渠道)配置
     * @return 结果
     */
    @Override
    public int insertAidAiProvider(AidAiProvider aidAiProvider)
    {
        aidAiProvider.setCreateTime(DateUtils.getNowDate());
        return this.save(aidAiProvider) ? 1 : 0;
    }

    /**
     * 修改AI大模型服务商(官方渠道)配置
     *
     * @param aidAiProvider AI大模型服务商(官方渠道)配置
     * @return 结果
     */
    @Override
    public int updateAidAiProvider(AidAiProvider aidAiProvider)
    {
        aidAiProvider.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidAiProvider) ? 1 : 0;
    }

    /**
     * 批量删除AI大模型服务商(官方渠道)配置
     *
     * @param ids 需要删除的AI大模型服务商(官方渠道)配置主键
     * @return 结果
     */
    @Override
    public int deleteAidAiProviderByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除AI大模型服务商(官方渠道)配置信息
     *
     * @param id AI大模型服务商(官方渠道)配置主键
     * @return 结果
     */
    @Override
    public int deleteAidAiProviderById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
