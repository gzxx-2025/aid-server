package com.aid.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidConfigService;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.service.IAiModelConfigService;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONException;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 默认模型解析服务。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AgentModelResolver
{
    /** 配置分类。 */
    private static final String CONFIG_CATEGORY = "agent_model";

    /** 配置键后缀。 */
    private static final String CONFIG_KEY_SUFFIX = "_model_code";

    /** 默认项目模式。 */
    private static final String DEFAULT_MODE = "economy";

    /** 模型编码字段。 */
    private static final String JSON_KEY_MODEL_CODE = "modelCode";

    /** 默认参数字段。 */
    private static final String JSON_KEY_DEFAULT_PARAMS = "defaultParams";

    @Autowired
    private IAidComicProjectService projectService;

    @Autowired
    private IAidConfigService aidConfigService;

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    /**
     * 解析默认模型编码。
     *
     * @param projectId 项目 ID
     * @param scene     Agent 场景
     * @return 默认模型编码
     */
    public String resolve(Long projectId, AgentScene scene)
    {
        return resolveDefault(projectId, scene).getModelCode();
    }

    /**
     * 解析默认模型和参数。
     *
     * @param projectId 项目 ID
     * @param scene     Agent 场景
     * @return 默认模型解析结果
     */
    public AgentModelDefault resolveDefault(Long projectId, AgentScene scene)
    {
        AidComicProject project = projectService.getOne(
                Wrappers.<AidComicProject>lambdaQuery()
                        .select(AidComicProject::getId, AidComicProject::getDefaultGenMode)
                        .eq(AidComicProject::getId, projectId));
        if (Objects.isNull(project))
        {
            log.info("Agent模型解析失败, 项目不存在: projectId={}", projectId);
            throw new RuntimeException("项目不存在");
        }

        String mode = StrUtil.blankToDefault(project.getDefaultGenMode(), DEFAULT_MODE);

        String primaryKey = scene.getModelType() + "_" + mode + CONFIG_KEY_SUFFIX;
        AgentModelDefault resolved = readConfigSafely(primaryKey);

        String legacyKey = scene.getKey() + "_" + mode + CONFIG_KEY_SUFFIX;
        if (resolved == null || StrUtil.isBlank(resolved.getModelCode()))
        {
            AgentModelDefault legacy = readConfigSafely(legacyKey);
            if (legacy != null && StrUtil.isNotBlank(legacy.getModelCode()))
            {
                log.info("Agent模型解析: 主key未配置, 走旧key兜底, primaryKey={}, legacyKey={}", primaryKey, legacyKey);
                resolved = legacy;
            }
        }

        if (resolved == null || StrUtil.isBlank(resolved.getModelCode()))
        {
            log.error("Agent模型解析失败, 主key与旧key均未配置: primaryKey={}, legacyKey={}", primaryKey, legacyKey);
            throw new RuntimeException("模型未配置");
        }

        String modelCode = resolved.getModelCode();

        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(modelConfig))
        {
            log.error("Agent模型解析失败, 模型不存在或不可用: modelCode={}", modelCode);
            throw new RuntimeException("模型不可用");
        }

        if (!Objects.equals(scene.getModelType(), modelConfig.getModelType()))
        {
            log.error("Agent模型解析失败, 模型类型不匹配: scene={}, expectedType={}, actualType={}, modelCode={}",
                    scene.getKey(), scene.getModelType(), modelConfig.getModelType(), modelCode);
            throw new RuntimeException("模型配置错误");
        }

        if ((Objects.equals("image", scene.getModelType()) || Objects.equals("video", scene.getModelType()))
                && !Boolean.TRUE.equals(modelConfig.getSupportsImageInput()))
        {
            log.error("Agent模型解析失败, 该分类默认模型必须支持图生图/图生视频: scene={}, modelCode={}, supportsImageInput={}",
                    scene.getKey(), modelCode, modelConfig.getSupportsImageInput());
            throw new RuntimeException("模型配置错误");
        }

        log.info("Agent模型解析: projectId={}, scene={}, mode={}, modelCode={}, defaultParams={}",
                projectId, scene.getKey(), mode, modelCode, resolved.getDefaultParams());
        return resolved;
    }

    /** {@link com.aid.aid.service.impl.AidConfigServiceImpl#getConfigValue} 在 row 缺失时抛出的异常消息标记 */
    private static final String CONFIG_NOT_CONFIGURED_MARKER = "未配置";

    /**
     * 读取模型配置。
     */
    private AgentModelDefault readConfigSafely(String configKey)
    {
        String raw;
        try
        {
            raw = aidConfigService.getConfigValue(CONFIG_CATEGORY, configKey);
        }
        catch (RuntimeException e)
        {
            String msg = e.getMessage();
            if (msg != null && msg.contains(CONFIG_NOT_CONFIGURED_MARKER))
            {
                log.info("aid_config 未配置: category={}, configKey={}", CONFIG_CATEGORY, configKey);
                return null;
            }
            log.error("aid_config 读取失败, 非'未配置'异常: category={}, configKey={}, err={}",
                    CONFIG_CATEGORY, configKey, msg);
            throw e;
        }
        return parseConfigValue(raw);
    }

    /**
     * 解析模型配置值。
     *
     * @param raw 配置值
     * @return 默认模型解析结果
     */
    public static AgentModelDefault parseConfigValue(String raw)
    {
        if (StrUtil.isBlank(raw))
        {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{"))
        {
            try
            {
                JSONObject obj = JSONUtil.parseObj(trimmed);
                String modelCode = obj.getStr(JSON_KEY_MODEL_CODE);
                Map<String, Object> defaultParams = new LinkedHashMap<>();
                JSONObject paramsObj = obj.getJSONObject(JSON_KEY_DEFAULT_PARAMS);
                if (paramsObj != null)
                {
                    for (String key : paramsObj.keySet())
                    {
                        Object value = paramsObj.get(key);
                        if (value != null)
                        {
                            defaultParams.put(key, value);
                        }
                    }
                }
                if (StrUtil.isBlank(modelCode))
                {
                    return null;
                }
                return new AgentModelDefault(modelCode, defaultParams);
            }
            catch (JSONException e)
            {
                log.error("aid_config agent_model JSON 解析失败, 回退按纯 modelCode 处理: raw={}, err={}",
                        trimmed, e.getMessage());
            }
        }
        return new AgentModelDefault(trimmed);
    }
}
