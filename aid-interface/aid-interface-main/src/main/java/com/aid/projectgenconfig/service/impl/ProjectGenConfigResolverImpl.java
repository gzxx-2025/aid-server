package com.aid.projectgenconfig.service.impl;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.agent.IAidAgentService;
import com.aid.aid.domain.AidAgent;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidProjectGenConfig;
import com.aid.aid.mapper.AidProjectGenConfigMapper;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelVO;
import com.aid.model.vo.CapabilityVO;
import com.aid.projectgenconfig.enums.ProjectGenConfigScene;
import com.aid.projectgenconfig.matrix.GenAgentMatrixResult;
import com.aid.projectgenconfig.matrix.IGenAgentMatrixResolver;
import com.aid.projectgenconfig.service.IProjectGenConfigResolver;
import com.aid.projectgenconfig.service.ResolvedSceneConfig;
import com.aid.service.IAiModelConfigService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 项目级生成配置解析器实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class ProjectGenConfigResolverImpl implements IProjectGenConfigResolver
{
    /** 删除标志：存在 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 生成策略 */
    private static final String MODE_PERFORMANCE = "performance";
    private static final String DEFAULT_MODE = "economy";

    /** 项目类型：电影（取项目 default_creation_mode；其它=剧集，取剧集 creation_mode） */
    private static final String PROJECT_TYPE_MOVIE = "movie";

    /** script_type 缺失时的默认分组（剧情演绎） */
    private static final String SCRIPT_TYPE_DEFAULT = "plot";

    /** capability_json 解析专用 ObjectMapper（忽略未知字段） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private AidProjectGenConfigMapper projectGenConfigMapper;

    @Autowired
    private IAidComicProjectService projectService;

    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;

    @Autowired
    private IAidAgentService aidAgentService;

    @Autowired
    private IAiModelBusinessService aiModelBusinessService;

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    @Autowired
    private IGenAgentMatrixResolver genAgentMatrixResolver;

    @Override
    public ResolvedSceneConfig resolve(Long projectId, Long userId, ProjectGenConfigScene scene,
                                       String requestedAgentCode, String requestedModelCode,
                                       String requestedResolution, String requestedAspectRatio)
    {
        // 不带剧集维度：分镜场景按项目默认创作模式解析
        return resolve(projectId, null, userId, scene, requestedAgentCode, requestedModelCode,
                requestedResolution, requestedAspectRatio);
    }

    @Override
    public ResolvedSceneConfig resolve(Long projectId, Long episodeId, Long userId, ProjectGenConfigScene scene,
                                       String requestedAgentCode, String requestedModelCode,
                                       String requestedResolution, String requestedAspectRatio)
    {
        if (Objects.isNull(scene))
        {
            log.error("项目配置解析失败: scene 为空, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("场景不可用");
        }

        String sceneCode = scene.getSceneCode();

        AidComicProject project = loadProject(projectId);
        String strategy = resolveStrategy(project);
        String scriptType = (Objects.nonNull(project) && StrUtil.isNotBlank(project.getScriptType()))
                ? project.getScriptType().trim() : SCRIPT_TYPE_DEFAULT;
        String creationMode = resolveCreationMode(project, episodeId, userId);

        GenAgentMatrixResult matrix = genAgentMatrixResolver.resolve(sceneCode, creationMode, scriptType, strategy);

        String agentCode = StrUtil.trim(requestedAgentCode);
        String modelCode = StrUtil.trim(requestedModelCode);
        String resolution = StrUtil.trim(requestedResolution);
        String aspectRatio = StrUtil.trim(requestedAspectRatio);

        if (isAnyKeyBlank(scene, agentCode, modelCode, resolution, aspectRatio))
        {
            // 任一关键字段缺失 → 先尝试项目级覆盖补齐
            AidProjectGenConfig saved = loadProjectConfig(projectId, userId, sceneCode);
            if (saved != null)
            {
                if (StrUtil.isBlank(agentCode))
                {
                    agentCode = StrUtil.trim(saved.getAgentCode());
                }
                if (StrUtil.isBlank(modelCode))
                {
                    modelCode = StrUtil.trim(saved.getModelCode());
                }
                if (scene.isNeedResolution() && StrUtil.isBlank(resolution))
                {
                    resolution = StrUtil.trim(saved.getResolution());
                }
                if (scene.isNeedAspectRatio() && StrUtil.isBlank(aspectRatio))
                {
                    aspectRatio = StrUtil.trim(saved.getAspectRatio());
                }
            }
        }

        if (isAnyKeyBlank(scene, agentCode, modelCode, resolution, aspectRatio)
                && matrix != null && matrix.isConfigured())
        {
            // 仍缺失 → 走矩阵默认
            if (StrUtil.isBlank(agentCode))
            {
                agentCode = StrUtil.trim(matrix.getAgentCode());
            }
            if (StrUtil.isBlank(modelCode))
            {
                modelCode = StrUtil.trim(matrix.getModelCode());
            }
            if (scene.isNeedResolution() && StrUtil.isBlank(resolution))
            {
                resolution = StrUtil.trim(matrix.getResolution());
            }
            if (scene.isNeedAspectRatio() && StrUtil.isBlank(aspectRatio))
            {
                aspectRatio = StrUtil.trim(matrix.getAspectRatio());
            }
        }

        if (StrUtil.isBlank(agentCode))
        {
            log.error("项目配置解析失败: 智能体未配置, projectId={}, userId={}, sceneCode={}",
                    projectId, userId, sceneCode);
            throw new ServiceException("智能体未配置");
        }
        if (StrUtil.isBlank(modelCode))
        {
            log.error("项目配置解析失败: 模型未配置, projectId={}, userId={}, sceneCode={}, agentCode={}",
                    projectId, userId, sceneCode, agentCode);
            throw new ServiceException("模型未配置");
        }
        if (scene.isNeedResolution() && StrUtil.isBlank(resolution))
        {
            log.error("项目配置解析失败: 清晰度未配置, projectId={}, userId={}, sceneCode={}, modelCode={}",
                    projectId, userId, sceneCode, modelCode);
            throw new ServiceException("清晰度未配置");
        }
        if (scene.isNeedAspectRatio() && StrUtil.isBlank(aspectRatio))
        {
            log.error("项目配置解析失败: 比例未配置, projectId={}, userId={}, sceneCode={}, modelCode={}",
                    projectId, userId, sceneCode, modelCode);
            throw new ServiceException("画面比例未配置");
        }

        AidAgent agent = aidAgentService.getByAgentCode(agentCode);
        if (Objects.isNull(agent))
        {
            log.error("项目配置解析失败: 智能体不存在或停用, projectId={}, sceneCode={}, agentCode={}",
                    projectId, sceneCode, agentCode);
            throw new ServiceException("智能体不可用");
        }
        // status=1 才视为启用；getByAgentCode 只过滤 del_flag，不看 status，此处补一道保险
        if (!Objects.equals(1, agent.getStatus()))
        {
            log.error("项目配置解析失败: 智能体已停用, projectId={}, sceneCode={}, agentCode={}, status={}",
                    projectId, sceneCode, agentCode, agent.getStatus());
            throw new ServiceException("智能体停用");
        }
        if (!Objects.equals(sceneCode, agent.getBizCategoryCode()))
        {
            log.error("项目配置解析失败: 智能体业务分类不符, projectId={}, sceneCode={}, agentCode={}, bizCategoryCode={}",
                    projectId, sceneCode, agentCode, agent.getBizCategoryCode());
            throw new ServiceException("智能体不匹配");
        }

        // 最终智能体必须命中当前创作配置的可选池。
        final String finalAgentCode = agentCode;
        if (!genAgentMatrixResolver.isAgentAllowed(sceneCode, creationMode, scriptType, finalAgentCode))
        {
            log.error("项目配置解析失败: 智能体不在可选池, projectId={}, sceneCode={}, creationMode={}, scriptType={}, strategy={}, agentCode={}",
                    projectId, sceneCode, creationMode, scriptType, strategy, finalAgentCode);
            throw new ServiceException("暂不可用");
        }

        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(modelConfig))
        {
            log.error("项目配置解析失败: 模型不存在, projectId={}, sceneCode={}, modelCode={}",
                    projectId, sceneCode, modelCode);
            throw new ServiceException("模型不存在");
        }
        if (!Objects.equals(scene.getModelType(), modelConfig.getModelType()))
        {
            log.error("项目配置解析失败: 模型类型不符, projectId={}, sceneCode={}, modelCode={}, expected={}, actual={}",
                    projectId, sceneCode, modelCode, scene.getModelType(), modelConfig.getModelType());
            throw new ServiceException("模型不可用");
        }
        List<AiModelVO> pool = aiModelBusinessService.listAvailableModelsByFuncCode(sceneCode);
        if (CollectionUtil.isEmpty(pool))
        {
            log.error("项目配置解析失败: 模型池未配置, projectId={}, sceneCode={}", projectId, sceneCode);
            throw new ServiceException("模型未配置");
        }
        final String finalModelCode = modelCode;
        boolean inPool = pool.stream().anyMatch(m -> Objects.equals(finalModelCode, m.getModelCode()));
        if (!inPool)
        {
            log.error("项目配置解析失败: 模型不在可选池内, projectId={}, sceneCode={}, modelCode={}, poolSize={}",
                    projectId, sceneCode, modelCode, pool.size());
            throw new ServiceException("模型不支持");
        }

        if (scene.isImageScene())
        {
            CapabilityVO capability = parseCapability(modelConfig.getCapabilityJson());
            if (scene.isNeedResolution() && !optionHit(resolution, capability.getSizeOptions()))
            {
                log.error("项目配置解析失败: 清晰度不在模型能力内, projectId={}, sceneCode={}, modelCode={}, resolution={}, sizeOptions={}",
                        projectId, sceneCode, modelCode, resolution, capability.getSizeOptions());
                throw new ServiceException("清晰度不支持");
            }
            if (scene.isNeedAspectRatio() && !optionHit(aspectRatio, capability.getAspectRatioOptions()))
            {
                log.error("项目配置解析失败: 比例不在模型能力内, projectId={}, sceneCode={}, modelCode={}, aspectRatio={}, aspectRatioOptions={}",
                        projectId, sceneCode, modelCode, aspectRatio, capability.getAspectRatioOptions());
                throw new ServiceException("画面比例不符");
            }
        }

        log.info("项目配置解析成功: projectId={}, episodeId={}, userId={}, sceneCode={}, agentCode={}, modelCode={}, resolution={}, aspectRatio={}",
                projectId, episodeId, userId, sceneCode, agentCode, modelCode, resolution, aspectRatio);

        return ResolvedSceneConfig.builder()
                .sceneCode(sceneCode)
                .agentCode(agentCode)
                .modelCode(modelCode)
                .resolution(scene.isNeedResolution() ? resolution : null)
                .aspectRatio(scene.isNeedAspectRatio() ? aspectRatio : null)
                .build();
    }
    /** 是否还有任一关键字段为空（含场景所需的清晰度/比例）。 */
    private boolean isAnyKeyBlank(ProjectGenConfigScene scene, String agentCode, String modelCode,
                                  String resolution, String aspectRatio)
    {
        return StrUtil.isBlank(agentCode) || StrUtil.isBlank(modelCode)
                || (scene.isNeedResolution() && StrUtil.isBlank(resolution))
                || (scene.isNeedAspectRatio() && StrUtil.isBlank(aspectRatio));
    }

    /** 一次性查项目（策略 + 创作模式 + 剧本类型）；缺失/异常返回 null。 */
    private AidComicProject loadProject(Long projectId)
    {
        if (Objects.isNull(projectId))
        {
            return null;
        }
        try
        {
            return projectService.getOne(
                    Wrappers.<AidComicProject>lambdaQuery()
                            .select(AidComicProject::getId, AidComicProject::getDefaultGenMode,
                                    AidComicProject::getProjectType, AidComicProject::getDefaultCreationMode,
                                    AidComicProject::getScriptType)
                            .eq(AidComicProject::getId, projectId));
        }
        catch (Exception e)
        {
            log.warn("项目查询失败，相关默认回退: projectId={}, err={}", projectId, e.getMessage());
            return null;
        }
    }

    /** 取项目当前生成策略；缺失/非法回退 economy。 */
    private String resolveStrategy(AidComicProject project)
    {
        if (Objects.isNull(project))
        {
            return DEFAULT_MODE;
        }
        return Objects.equals(MODE_PERFORMANCE, project.getDefaultGenMode()) ? MODE_PERFORMANCE : DEFAULT_MODE;
    }

    /**
     * 解析项目/剧集的创作模式（矩阵选智能体维度）。
     * 电影类项目取 {@code default_creation_mode}；剧集类项目且传入 episodeId 时取该剧集
     * {@code creation_mode}（带归属校验），剧集无效/未传则回退项目默认。
     */
    private String resolveCreationMode(AidComicProject project, Long episodeId, Long userId)
    {
        if (Objects.isNull(project))
        {
            return "";
        }
        if (!Objects.equals(PROJECT_TYPE_MOVIE, project.getProjectType()) && Objects.nonNull(episodeId))
        {
            AidComicEpisode episode = aidComicEpisodeService.getOne(
                    Wrappers.<AidComicEpisode>lambdaQuery()
                            .eq(AidComicEpisode::getId, episodeId)
                            .eq(AidComicEpisode::getProjectId, project.getId())
                            .eq(AidComicEpisode::getUserId, userId)
                            .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL)
                            .last("limit 1"));
            if (Objects.nonNull(episode) && StrUtil.isNotBlank(episode.getCreationMode()))
            {
                return episode.getCreationMode().trim();
            }
            log.info("剧集创作模式缺失或剧集无效，回退项目默认创作模式: projectId={}, episodeId={}",
                    project.getId(), episodeId);
        }
        return StrUtil.trimToEmpty(project.getDefaultCreationMode());
    }

    /** 查项目级覆盖（项目+用户+场景）。 */
    private AidProjectGenConfig loadProjectConfig(Long projectId, Long userId, String sceneCode)
    {
        if (Objects.isNull(projectId) || Objects.isNull(userId) || StrUtil.isBlank(sceneCode))
        {
            return null;
        }
        return projectGenConfigMapper.selectOne(
                Wrappers.<AidProjectGenConfig>lambdaQuery()
                        .eq(AidProjectGenConfig::getProjectId, projectId)
                        .eq(AidProjectGenConfig::getUserId, userId)
                        .eq(AidProjectGenConfig::getSceneCode, sceneCode)
                        .eq(AidProjectGenConfig::getDelFlag, DEL_FLAG_NORMAL)
                        .last("limit 1"));
    }

    /** 解析 capability_json 为 CapabilityVO；空或失败抛"模型不符"。 */
    private CapabilityVO parseCapability(String capabilityJson)
    {
        if (StrUtil.isBlank(capabilityJson))
        {
            log.error("项目配置解析失败: capabilityJson 为空");
            throw new ServiceException("模型不支持");
        }
        try
        {
            CapabilityVO capability = OBJECT_MAPPER.readValue(capabilityJson, CapabilityVO.class);
            if (Objects.isNull(capability))
            {
                log.error("项目配置解析失败: capabilityJson 解析为空");
                throw new ServiceException("模型不支持");
            }
            return capability;
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("项目配置解析失败: capabilityJson 解析异常, err={}", e.getMessage());
            throw new ServiceException("模型不支持");
        }
    }

    /** 候选项命中：去首尾空白 + 忽略大小写完全相等。 */
    private boolean optionHit(String value, List<String> options)
    {
        if (StrUtil.isBlank(value) || CollectionUtil.isEmpty(options))
        {
            return false;
        }
        String target = value.trim();
        return options.stream()
                .anyMatch(o -> StrUtil.isNotBlank(o) && o.trim().equalsIgnoreCase(target));
    }
}
