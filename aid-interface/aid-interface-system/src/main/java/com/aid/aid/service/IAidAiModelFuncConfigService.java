package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidAiModelFuncConfig;

/**
 * AI模型功能配置Service接口
 *
 * @author 视觉AID
 */
public interface IAidAiModelFuncConfigService extends IService<AidAiModelFuncConfig>
{
    /**
     * 查询AI模型功能配置
     *
     * @param id AI模型功能配置主键
     * @return AI模型功能配置
     */
    public AidAiModelFuncConfig selectAidAiModelFuncConfigById(Long id);

    /**
     * 查询AI模型功能配置列表
     *
     * @param aidAiModelFuncConfig AI模型功能配置
     * @return AI模型功能配置集合
     */
    public List<AidAiModelFuncConfig> selectAidAiModelFuncConfigList(AidAiModelFuncConfig aidAiModelFuncConfig);

    /**
     * 新增AI模型功能配置
     *
     * @param aidAiModelFuncConfig AI模型功能配置
     * @return 结果
     */
    public int insertAidAiModelFuncConfig(AidAiModelFuncConfig aidAiModelFuncConfig);

    /**
     * 修改AI模型功能配置
     *
     * @param aidAiModelFuncConfig AI模型功能配置
     * @return 结果
     */
    public int updateAidAiModelFuncConfig(AidAiModelFuncConfig aidAiModelFuncConfig);

    /**
     * 批量删除AI模型功能配置
     *
     * @param ids 需要删除的AI模型功能配置主键集合
     * @return 结果
     */
    public int deleteAidAiModelFuncConfigByIds(Long[] ids);

    /**
     * 删除AI模型功能配置信息
     *
     * @param id AI模型功能配置主键
     * @return 结果
     */
    public int deleteAidAiModelFuncConfigById(Long id);
}
