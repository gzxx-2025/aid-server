package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.mapper.AidAiProviderMapper;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.GatewayUrlUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI大模型服务商(官方渠道)配置Service业务层处理
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AidAiProviderServiceImpl extends ServiceImpl<AidAiProviderMapper, AidAiProvider> implements IAidAiProviderService
{
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
        validateAndNormalizeBaseUrl(aidAiProvider);
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
        validateAndNormalizeBaseUrl(aidAiProvider);
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

    /**
     * 校验并规范化服务商基础网关地址。
     *
     * @param aidAiProvider 服务商配置
     */
    private void validateAndNormalizeBaseUrl(AidAiProvider aidAiProvider)
    {
        if (Objects.isNull(aidAiProvider) || StrUtil.isBlank(aidAiProvider.getBaseUrl()))
        {
            log.error("保存服务商失败, API基础网关为空");
            throw new ServiceException("网关地址不能为空");
        }
        String baseUrl = aidAiProvider.getBaseUrl().trim();
        if (!GatewayUrlUtils.isBaseGatewayUrl(baseUrl))
        {
            log.error("保存服务商失败, API网关不是基础地址, baseUrl={}", baseUrl);
            throw new ServiceException("请填写基础网关");
        }
        aidAiProvider.setBaseUrl(GatewayUrlUtils.normalizeBaseGatewayUrl(baseUrl));
    }
}
