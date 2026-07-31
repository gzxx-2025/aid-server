package com.aid.agent;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;

/**
 * Agent 默认模型解析结果。
 *
 * @author 视觉AID
 */
@Data
public class AgentModelDefault
{
    /** 默认模型编码。 */
    private String modelCode;

    /** 默认参数。 */
    private Map<String, Object> defaultParams = new LinkedHashMap<>();

    public AgentModelDefault()
    {
    }

    public AgentModelDefault(String modelCode)
    {
        this.modelCode = modelCode;
    }

    public AgentModelDefault(String modelCode, Map<String, Object> defaultParams)
    {
        this.modelCode = modelCode;
        if (defaultParams != null)
        {
            this.defaultParams = new LinkedHashMap<>(defaultParams);
        }
    }

    /** 默认参数是否非空。 */
    public boolean hasDefaultParams()
    {
        return defaultParams != null && !defaultParams.isEmpty();
    }
}
