package com.aid.common.aid.core.service;


import java.util.List;
import java.util.Map;

/**
 * 通用 参数配置服务
 */
public interface ConfigService {

    /**
     * 根据配置类型和配置key获取值
     *
     * @param category
     * @param configKey
     * @return
     */
    String getConfigValue(String category, String configKey);


    /**
     * 根据分类获取值
     *
     * @param category
     * @return
     */
    Map<String, String> getConfigValues(String category);
}
