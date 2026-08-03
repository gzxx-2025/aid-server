package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidAiProvider;

/**
 * AI大模型服务商(官方渠道)配置Service接口
 *
 * @author 视觉AID
 */
public interface IAidAiProviderService extends IService<AidAiProvider>
{
    /**
     * 查询AI大模型服务商(官方渠道)配置
     *
     * @param id AI大模型服务商(官方渠道)配置主键
     * @return AI大模型服务商(官方渠道)配置
     */
    public AidAiProvider selectAidAiProviderById(Long id);

    /**
     * 查询AI大模型服务商(官方渠道)配置列表
     *
     * @param aidAiProvider AI大模型服务商(官方渠道)配置
     * @return AI大模型服务商(官方渠道)配置集合
     */
    public List<AidAiProvider> selectAidAiProviderList(AidAiProvider aidAiProvider);

    /**
     * 新增AI大模型服务商(官方渠道)配置
     *
     * @param aidAiProvider AI大模型服务商(官方渠道)配置
     * @return 结果
     */
    public int insertAidAiProvider(AidAiProvider aidAiProvider);

    /**
     * 修改AI大模型服务商(官方渠道)配置
     *
     * @param aidAiProvider AI大模型服务商(官方渠道)配置
     * @return 结果
     */
    public int updateAidAiProvider(AidAiProvider aidAiProvider);

    /**
     * 修改AI大模型服务商启停状态。
     *
     * @param id 服务商主键
     * @param status 目标状态：0启用，1停用
     * @param updateBy 更新者
     * @return 结果
     */
    public int updateAidAiProviderStatus(Long id, String status, String updateBy);

    /**
     * 批量删除AI大模型服务商(官方渠道)配置
     *
     * @param ids 需要删除的AI大模型服务商(官方渠道)配置主键集合
     * @return 结果
     */
    public int deleteAidAiProviderByIds(Long[] ids);

    /**
     * 删除AI大模型服务商(官方渠道)配置信息
     *
     * @param id AI大模型服务商(官方渠道)配置主键
     * @return 结果
     */
    public int deleteAidAiProviderById(Long id);
}
