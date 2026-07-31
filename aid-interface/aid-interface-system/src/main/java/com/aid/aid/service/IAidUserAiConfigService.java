package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidUserAiConfig;

/**
 * 用户自定义AI大模型配置(配置覆盖用)Service接口
 *
 * @author 视觉AID
 */
public interface IAidUserAiConfigService extends IService<AidUserAiConfig>
{
    /**
     * 查询用户自定义AI大模型配置(配置覆盖用)
     *
     * @param id 用户自定义AI大模型配置(配置覆盖用)主键
     * @return 用户自定义AI大模型配置(配置覆盖用)
     */
    public AidUserAiConfig selectAidUserAiConfigById(Long id);

    /**
     * 查询用户自定义AI大模型配置(配置覆盖用)列表
     *
     * @param aidUserAiConfig 用户自定义AI大模型配置(配置覆盖用)
     * @return 用户自定义AI大模型配置(配置覆盖用)集合
     */
    public List<AidUserAiConfig> selectAidUserAiConfigList(AidUserAiConfig aidUserAiConfig);

    /**
     * 新增用户自定义AI大模型配置(配置覆盖用)
     *
     * @param aidUserAiConfig 用户自定义AI大模型配置(配置覆盖用)
     * @return 结果
     */
    public int insertAidUserAiConfig(AidUserAiConfig aidUserAiConfig);

    /**
     * 修改用户自定义AI大模型配置(配置覆盖用)
     *
     * @param aidUserAiConfig 用户自定义AI大模型配置(配置覆盖用)
     * @return 结果
     */
    public int updateAidUserAiConfig(AidUserAiConfig aidUserAiConfig);

    /**
     * 批量删除用户自定义AI大模型配置(配置覆盖用)
     *
     * @param ids 需要删除的用户自定义AI大模型配置(配置覆盖用)主键集合
     * @return 结果
     */
    public int deleteAidUserAiConfigByIds(Long[] ids);

    /**
     * 删除用户自定义AI大模型配置(配置覆盖用)信息
     *
     * @param id 用户自定义AI大模型配置(配置覆盖用)主键
     * @return 结果
     */
    public int deleteAidUserAiConfigById(Long id);
}
