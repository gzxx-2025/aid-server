package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidConfig;

/**
 * 配置信息Service接口
 *
 * @author 视觉AID
 */
public interface IAidConfigService extends IService<AidConfig>
{
    /**
     * 查询配置信息
     *
     * @param id 配置信息主键
     * @return 配置信息
     */
    public AidConfig selectAidConfigById(Long id);

    /**
     * 查询配置信息列表
     *
     * @param aidConfig 配置信息
     * @return 配置信息集合
     */
    public List<AidConfig> selectAidConfigList(AidConfig aidConfig);

    /**
     * 新增配置信息
     *
     * @param aidConfig 配置信息
     * @return 结果
     */
    public int insertAidConfig(AidConfig aidConfig);

    /**
     * 修改配置信息
     *
     * @param aidConfig 配置信息
     * @return 结果
     */
    public int updateAidConfig(AidConfig aidConfig);

    /**
     * 批量删除配置信息
     *
     * @param ids 需要删除的配置信息主键集合
     * @return 结果
     */
    public int deleteAidConfigByIds(Long[] ids);

    /**
     * 删除配置信息信息
     *
     * @param id 配置信息主键
     * @return 结果
     */
    public int deleteAidConfigById(Long id);

    /**
     * 根据分类和配置名获取配置值
     *
     * @param category  配置分类
     * @param configKey 配置名
     * @return 配置值，不存在返回null
     */
    String getConfigValue(String category, String configKey);

    /**
     * 按「分类 + 配置名」精确 upsert 配置值：存在则更新，不存在则新增。
     *
     * @param category    配置分类
     * @param configName  配置名
     * @param configValue 配置值
     */
    void upsertConfigValue(String category, String configName, String configValue);
}
