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
import com.aid.aid.mapper.AidUserAiConfigMapper;
import com.aid.aid.domain.AidUserAiConfig;
import com.aid.aid.service.IAidUserAiConfigService;

/**
 * 用户自定义AI大模型配置(配置覆盖用)Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidUserAiConfigServiceImpl extends ServiceImpl<AidUserAiConfigMapper, AidUserAiConfig> implements IAidUserAiConfigService
{
    @Autowired
    private AidUserAiConfigMapper aidUserAiConfigMapper;

    /**
     * 查询用户自定义AI大模型配置(配置覆盖用)
     *
     * @param id 用户自定义AI大模型配置(配置覆盖用)主键
     * @return 用户自定义AI大模型配置(配置覆盖用)
     */
    @Override
    public AidUserAiConfig selectAidUserAiConfigById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询用户自定义AI大模型配置(配置覆盖用)列表
     *
     * @param aidUserAiConfig 用户自定义AI大模型配置(配置覆盖用)
     * @return 用户自定义AI大模型配置(配置覆盖用)
     */
    @Override
    public List<AidUserAiConfig> selectAidUserAiConfigList(AidUserAiConfig aidUserAiConfig)
    {
        LambdaQueryWrapper<AidUserAiConfig> wrapper = Wrappers.lambdaQuery();
        if (aidUserAiConfig != null)
        {
            if (aidUserAiConfig.getUserId() != null)
            {
                wrapper.eq(AidUserAiConfig::getUserId, aidUserAiConfig.getUserId());
            }
            if (aidUserAiConfig.getProviderId() != null)
            {
                wrapper.eq(AidUserAiConfig::getProviderId, aidUserAiConfig.getProviderId());
            }
            if (StrUtil.isNotBlank(aidUserAiConfig.getIsEnable()))
            {
                wrapper.eq(AidUserAiConfig::getIsEnable, aidUserAiConfig.getIsEnable());
            }
        }
        wrapper.orderByDesc(AidUserAiConfig::getId);
        return this.list(wrapper);
    }

    /**
     * 新增用户自定义AI大模型配置(配置覆盖用)
     *
     * @param aidUserAiConfig 用户自定义AI大模型配置(配置覆盖用)
     * @return 结果
     */
    @Override
    public int insertAidUserAiConfig(AidUserAiConfig aidUserAiConfig)
    {
        aidUserAiConfig.setCreateTime(DateUtils.getNowDate());
        return this.save(aidUserAiConfig) ? 1 : 0;
    }

    /**
     * 修改用户自定义AI大模型配置(配置覆盖用)
     *
     * @param aidUserAiConfig 用户自定义AI大模型配置(配置覆盖用)
     * @return 结果
     */
    @Override
    public int updateAidUserAiConfig(AidUserAiConfig aidUserAiConfig)
    {
        aidUserAiConfig.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidUserAiConfig) ? 1 : 0;
    }

    /**
     * 批量删除用户自定义AI大模型配置(配置覆盖用)
     *
     * @param ids 需要删除的用户自定义AI大模型配置(配置覆盖用)主键
     * @return 结果
     */
    @Override
    public int deleteAidUserAiConfigByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除用户自定义AI大模型配置(配置覆盖用)信息
     *
     * @param id 用户自定义AI大模型配置(配置覆盖用)主键
     * @return 结果
     */
    @Override
    public int deleteAidUserAiConfigById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
