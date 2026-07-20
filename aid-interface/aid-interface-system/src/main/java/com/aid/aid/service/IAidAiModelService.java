package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.vo.AidRealModelGroupVo;

/**
 * AI底层模型配置与算力计费Service接口
 *
 * @author 视觉AID
 */
public interface IAidAiModelService extends IService<AidAiModel>
{
    /**
     * 查询AI底层模型配置与算力计费
     *
     * @param id AI底层模型配置与算力计费主键
     * @return AI底层模型配置与算力计费
     */
    public AidAiModel selectAidAiModelById(Long id);

    /**
     * 查询AI底层模型配置与算力计费列表
     *
     * @param aidAiModel AI底层模型配置与算力计费
     * @return AI底层模型配置与算力计费集合
     */
    public List<AidAiModel> selectAidAiModelList(AidAiModel aidAiModel);

    /**
     * 新增AI底层模型配置与算力计费
     *
     * @param aidAiModel AI底层模型配置与算力计费
     * @return 结果
     */
    public int insertAidAiModel(AidAiModel aidAiModel);

    /**
     * 修改AI底层模型配置与算力计费
     *
     * @param aidAiModel AI底层模型配置与算力计费
     * @return 结果
     */
    public int updateAidAiModel(AidAiModel aidAiModel);

    /**
     * 批量删除AI底层模型配置与算力计费
     *
     * @param ids 需要删除的AI底层模型配置与算力计费主键集合
     * @return 结果
     */
    public int deleteAidAiModelByIds(Long[] ids);

    /**
     * 删除AI底层模型配置与算力计费信息
     *
     * @param id AI底层模型配置与算力计费主键
     * @return 结果
     */
    public int deleteAidAiModelById(Long id);

    /**
     * 真实模型总览（按真实上游模型名聚合，含各模型启停状态与所属服务商）
     *
     * @param keyword 搜索关键字（匹配真实模型名/展示码/展示名称，可空）
     * @return 真实模型分组列表
     */
    List<AidRealModelGroupVo> selectRealModelOverview(String keyword);
}
