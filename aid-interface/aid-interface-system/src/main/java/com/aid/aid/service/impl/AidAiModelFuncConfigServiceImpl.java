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
import com.aid.aid.mapper.AidAiModelFuncConfigMapper;
import com.aid.aid.domain.AidAiModelFuncConfig;
import com.aid.aid.service.IAidAiModelFuncConfigService;

/**
 * AI模型功能配置Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidAiModelFuncConfigServiceImpl extends ServiceImpl<AidAiModelFuncConfigMapper, AidAiModelFuncConfig> implements IAidAiModelFuncConfigService
{
    @Autowired
    private AidAiModelFuncConfigMapper aidAiModelFuncConfigMapper;

    /**
     * 查询AI模型功能配置
     *
     * @param id AI模型功能配置主键
     * @return AI模型功能配置
     */
    @Override
    public AidAiModelFuncConfig selectAidAiModelFuncConfigById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询AI模型功能配置列表
     *
     * @param aidAiModelFuncConfig AI模型功能配置
     * @return AI模型功能配置
     */
    @Override
    public List<AidAiModelFuncConfig> selectAidAiModelFuncConfigList(AidAiModelFuncConfig aidAiModelFuncConfig)
    {
        LambdaQueryWrapper<AidAiModelFuncConfig> wrapper = Wrappers.lambdaQuery();
        if (aidAiModelFuncConfig != null)
        {
            if (StrUtil.isNotBlank(aidAiModelFuncConfig.getFuncName()))
            {
                wrapper.like(AidAiModelFuncConfig::getFuncName, aidAiModelFuncConfig.getFuncName());
            }
            if (StrUtil.isNotBlank(aidAiModelFuncConfig.getFuncCode()))
            {
                wrapper.like(AidAiModelFuncConfig::getFuncCode, aidAiModelFuncConfig.getFuncCode());
            }
            if (StrUtil.isNotBlank(aidAiModelFuncConfig.getModelType()))
            {
                wrapper.eq(AidAiModelFuncConfig::getModelType, aidAiModelFuncConfig.getModelType());
            }
            if (StrUtil.isNotBlank(aidAiModelFuncConfig.getGenerateMode()))
            {
                wrapper.eq(AidAiModelFuncConfig::getGenerateMode, aidAiModelFuncConfig.getGenerateMode());
            }
            if (StrUtil.isNotBlank(aidAiModelFuncConfig.getStatus()))
            {
                wrapper.eq(AidAiModelFuncConfig::getStatus, aidAiModelFuncConfig.getStatus());
            }
        }
        wrapper.orderByDesc(AidAiModelFuncConfig::getId);
        return this.list(wrapper);
    }

    /**
     * 新增AI模型功能配置
     *
     * @param aidAiModelFuncConfig AI模型功能配置
     * @return 结果
     */
    @Override
    public int insertAidAiModelFuncConfig(AidAiModelFuncConfig aidAiModelFuncConfig)
    {
        aidAiModelFuncConfig.setCreateTime(DateUtils.getNowDate());
        return this.save(aidAiModelFuncConfig) ? 1 : 0;
    }

    /**
     * 修改AI模型功能配置
     *
     * @param aidAiModelFuncConfig AI模型功能配置
     * @return 结果
     */
    @Override
    public int updateAidAiModelFuncConfig(AidAiModelFuncConfig aidAiModelFuncConfig)
    {
        aidAiModelFuncConfig.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidAiModelFuncConfig) ? 1 : 0;
    }

    /**
     * 批量删除AI模型功能配置
     *
     * @param ids 需要删除的AI模型功能配置主键
     * @return 结果
     */
    @Override
    public int deleteAidAiModelFuncConfigByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除AI模型功能配置信息
     *
     * @param id AI模型功能配置主键
     * @return 结果
     */
    @Override
    public int deleteAidAiModelFuncConfigById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
