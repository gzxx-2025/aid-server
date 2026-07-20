package com.aid.projectgenconfig.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.agent.IAidAgentService;
import com.aid.agent.vo.AgentInfoVO;
import com.aid.agent.vo.AgentListGroupVO;
import com.aid.aid.domain.AidAgent;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidProjectGenConfig;
import com.aid.aid.mapper.AidProjectGenConfigMapper;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.SecurityUtils;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelVO;
import com.aid.model.vo.CapabilityVO;
import com.aid.projectgenconfig.dto.ProjectGenConfigItem;
import com.aid.projectgenconfig.dto.SaveProjectGenConfigRequest;
import com.aid.projectgenconfig.enums.ProjectGenConfigScene;
import com.aid.projectgenconfig.matrix.GenAgentMatrixResult;
import com.aid.projectgenconfig.matrix.IGenAgentMatrixResolver;
import com.aid.projectgenconfig.service.IProjectGenConfigService;
import com.aid.projectgenconfig.vo.ProjectGenConfigVO;
import com.aid.service.IAiModelConfigService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 项目级生成配置服务实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class ProjectGenConfigServiceImpl implements IProjectGenConfigService
{
    /** 删除标志：存在 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** script_type 缺失时的默认分组（剧情演绎），兼容历史无 script_type 的项目 */
    private static final String SCRIPT_TYPE_DEFAULT = "plot";

    /** 项目类型：电影（取项目 default_creation_mode；其它=剧集，取剧集 creation_mode） */
    private static final String PROJECT_TYPE_MOVIE = "movie";

    /** 生成模式：经济 / 性能；默认经济 */
    private static final String MODE_ECONOMY = "economy";
    private static final String MODE_PERFORMANCE = "performance";
    private static final String DEFAULT_MODE = MODE_ECONOMY;

    /** 取值来源标识 */
    private static final String SOURCE_PROJECT = "project";
    private static final String SOURCE_DEFAULT = "default";
    private static final String SOURCE_NONE = "none";

    /** capability_json 解析专用 ObjectMapper（忽略未知字段） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private AidProjectGenConfigMapper projectGenConfigMapper;

    @Autowired
    private IAidComicProjectService projectService;

    @Autowired
    private IAidAgentService aidAgentService;

    @Autowired
    private IAiModelBusinessService aiModelBusinessService;

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    @Autowired
    private IGenAgentMatrixResolver genAgentMatrixResolver;

    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ProjectGenConfigVO> saveConfig(SaveProjectGenConfigRequest request, Long userId)
    {
        Long projectId = request.getProjectId();
        AidComicProject project = assertProjectOwnership(projectId, userId);

        List<ProjectGenConfigItem> configs = request.getConfigs();
        if (CollectionUtil.isEmpty(configs))
        {
            log.error("保存项目生成配置失败: 配置列表为空, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("配置不能为空");
        }

        // 保存为持久化偏好、不触发生成：智能体只校验是否为该场景矩阵里的真实候选（任意创作模式），
        // 不按当前创作模式卡死，允许提前为暂不适用场景预设配置；生成时再按当前模式严格把关
        List<AidProjectGenConfig> toPersist = new ArrayList<>();
        for (ProjectGenConfigItem item : configs)
        {
            toPersist.add(validateItem(projectId, userId, item));
        }

        String operator = currentOperator(userId);
        List<ProjectGenConfigVO> result = new ArrayList<>();
        for (AidProjectGenConfig entity : toPersist)
        {
            upsert(entity, operator);
            result.add(toVO(entity, SOURCE_PROJECT));
        }
        log.info("保存项目生成配置成功: projectId={}, userId={}, sceneCount={}", projectId, userId, result.size());
        return result;
    }

    @Override
    public List<ProjectGenConfigVO> listConfig(Long projectId, Long episodeId, Long userId)
    {
        // 读取同样做归属校验，避免越权读取他人项目配置
        AidComicProject project = assertProjectOwnership(projectId, userId);
        // 兜底取值模式：项目 default_gen_mode（economy/performance），缺省/非法回退 economy
        String mode = normalizeMode(project.getDefaultGenMode());
        // 剧本类型（plot/monologue），缺失默认 plot
        String scriptType = StrUtil.isBlank(project.getScriptType())
                ? SCRIPT_TYPE_DEFAULT : project.getScriptType().trim();
        // 创作模式：电影=项目 default_creation_mode；剧集=对应剧集 creation_mode（无 episodeId 回退项目默认）
        String creationMode = resolveCreationMode(project, episodeId, userId);

        Map<String, AidProjectGenConfig> savedMap = loadProjectConfigs(projectId, userId);

        // 逐场景经矩阵解析默认值（分镜场景按 创作模式×剧本类型×策略 取行，其余场景命中通配 * 行），项目级覆盖优先
        List<ProjectGenConfigVO> result = new ArrayList<>(ProjectGenConfigScene.values().length);
        for (ProjectGenConfigScene scene : ProjectGenConfigScene.values())
        {
            String sceneCode = scene.getSceneCode();
            GenAgentMatrixResult matrix = genAgentMatrixResolver.resolve(sceneCode, creationMode, scriptType, mode);
            // 该场景在当前创作模式下不适用（矩阵无该 biz×创作模式 配置）→ 直接不返回，
            // 无用场景前端无需展示（如 i2v 下的多参/宫格视频提示词、专业版无分镜图提示词等）。
            if (!matrix.isConfigured())
            {
                continue;
            }
            AidProjectGenConfig saved = savedMap.get(sceneCode);
            ProjectGenConfigVO vo;
            if (Objects.nonNull(saved))
            {
                // 项目级已显式保存覆盖：优先用项目值
                vo = toVO(saved, SOURCE_PROJECT);
            }
            else if (StrUtil.isNotBlank(matrix.getAgentCode()))
            {
                vo = ProjectGenConfigVO.builder()
                        .sceneCode(sceneCode)
                        .agentCode(matrix.getAgentCode())
                        .modelCode(matrix.getModelCode())
                        .resolution(scene.isNeedResolution() ? matrix.getResolution() : null)
                        .aspectRatio(scene.isNeedAspectRatio() ? matrix.getAspectRatio() : null)
                        .source(SOURCE_DEFAULT)
                        .mode(mode)
                        .build();
            }
            else
            {
                // 适用但矩阵未给默认智能体（缺该剧本类型×策略默认行）：返回空壳 + 可选池，供前端自选
                vo = noneVO(scene);
            }
            vo.setAgentPool(matrix.getAgentPool());
            // 走到此处必然适用（不适用的已 continue 跳过）
            vo.setApplicable(true);
            // 附加该场景的可选模型池（含 capability）：funcCode = sceneCode
            vo.setAvailableModels(aiModelBusinessService.listAvailableModelsByFuncCode(sceneCode));
            result.add(vo);
        }

        // 用各场景的可选池 agentPool(agentCode) 关联 aid_agent 取名，顺序与 agentPool 一致；
        // 前端据此直接渲染下拉，避免通用「按业务分类列全部智能体」接口带出当前创作模式不该出现的智能体
        fillAgentOptions(result);
        return result;
    }

    /**
     * 按各场景可选池(agentPool) 关联 aid_agent 名称，填充带名称的下拉项 agentOptions（顺序与池一致）。
     * 一次批量查询启用智能体（按业务分类分组），再按池过滤，避免逐场景多次查库。
     */
    private void fillAgentOptions(List<ProjectGenConfigVO> result)
    {
        if (CollectionUtil.isEmpty(result))
        {
            return;
        }
        // 本次返回的全部场景编码（=业务分类编码），一次批量取启用智能体
        List<String> sceneCodes = result.stream()
                .map(ProjectGenConfigVO::getSceneCode)
                .collect(Collectors.toList());
        Map<String, Map<String, AgentInfoVO>> sceneAgentMap = new HashMap<>();
        for (AgentListGroupVO group : aidAgentService.listEnabledAgentsGroupedForClient(sceneCodes))
        {
            Map<String, AgentInfoVO> byCode = new HashMap<>();
            if (CollectionUtil.isNotEmpty(group.getAgents()))
            {
                for (AgentInfoVO a : group.getAgents())
                {
                    byCode.put(a.getAgentCode(), a);
                }
            }
            sceneAgentMap.put(group.getBizCategoryCode(), byCode);
        }
        // 按可选池顺序组装下拉项；池里有但智能体已停用/删除的会被自然过滤掉
        for (ProjectGenConfigVO vo : result)
        {
            Map<String, AgentInfoVO> byCode = sceneAgentMap.getOrDefault(vo.getSceneCode(), Collections.emptyMap());
            List<AgentInfoVO> options = new ArrayList<>();
            if (CollectionUtil.isNotEmpty(vo.getAgentPool()))
            {
                for (String code : vo.getAgentPool())
                {
                    AgentInfoVO a = byCode.get(code);
                    if (Objects.nonNull(a))
                    {
                        options.add(a);
                    }
                }
            }
            vo.setAgentOptions(options);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int clearProjectConfig(Long projectId, Long userId)
    {
        if (Objects.isNull(projectId) || Objects.isNull(userId))
        {
            return 0;
        }
        // 软删除该项目+用户下全部有效配置行（del_flag 0→1），并补更新者/更新时间
        int rows = projectGenConfigMapper.update(null,
                Wrappers.<AidProjectGenConfig>lambdaUpdate()
                        .set(AidProjectGenConfig::getDelFlag, "1")
                        .set(AidProjectGenConfig::getUpdateBy, currentOperator(userId))
                        .set(AidProjectGenConfig::getUpdateTime, DateUtils.getNowDate())
                        .eq(AidProjectGenConfig::getProjectId, projectId)
                        .eq(AidProjectGenConfig::getUserId, userId)
                        .eq(AidProjectGenConfig::getDelFlag, DEL_FLAG_NORMAL));
        log.info("清空项目级生成配置(创作模式跨组切换): projectId={}, userId={}, rows={}", projectId, userId, rows);
        return rows;
    }

    /**
     * 一次性查询项目配置表 (项目+用户) 下所有场景的有效记录，按 sceneCode 建索引返回。
     */
    private Map<String, AidProjectGenConfig> loadProjectConfigs(Long projectId, Long userId)
    {
        List<AidProjectGenConfig> rows = projectGenConfigMapper.selectList(
                Wrappers.<AidProjectGenConfig>lambdaQuery()
                        .eq(AidProjectGenConfig::getProjectId, projectId)
                        .eq(AidProjectGenConfig::getUserId, userId)
                        .eq(AidProjectGenConfig::getDelFlag, DEL_FLAG_NORMAL));
        if (CollectionUtil.isEmpty(rows))
        {
            return Collections.emptyMap();
        }
        // 唯一键 (project_id, user_id, scene_code) 保证 sceneCode 不会重复，遇重复时取后者
        return rows.stream().collect(Collectors.toMap(
                AidProjectGenConfig::getSceneCode,
                r -> r,
                (a, b) -> b));
    }

    /**
     * 归一化生成模式：仅接受 economy / performance，其余一律回退 economy。
     */
    private String normalizeMode(String mode)
    {
        if (Objects.equals(MODE_PERFORMANCE, mode))
        {
            return MODE_PERFORMANCE;
        }
        return DEFAULT_MODE;
    }

    /**
     * 解析项目/剧集的创作模式（用于矩阵选智能体）。
     *
     * @param project   项目
     * @param episodeId 剧集ID（可空）
     * @param userId    当前用户（剧集归属校验）
     * @return 创作模式（可能为空字符串，表示项目未设创作模式）
     */
    private String resolveCreationMode(AidComicProject project, Long episodeId, Long userId)
    {
        // 剧集类项目 + 指定剧集：取剧集创作模式（带归属校验，防越权）
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
        // 电影 或 未指定剧集：用项目默认创作模式
        return StrUtil.trimToEmpty(project.getDefaultCreationMode());
    }
    /**
     * 项目归属校验：存在 + 未删除 + 属当前用户。
     */
    private AidComicProject assertProjectOwnership(Long projectId, Long userId)
    {
        if (Objects.isNull(projectId))
        {
            log.error("项目生成配置校验失败: projectId 为空, userId={}", userId);
            throw new ServiceException("项目不能为空");
        }
        AidComicProject project = projectService.selectAidComicProjectById(projectId);
        if (Objects.isNull(project) || !Objects.equals(DEL_FLAG_NORMAL, project.getDelFlag()))
        {
            log.error("项目生成配置校验失败: 项目不存在或已删除, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("项目不存在");
        }
        if (!Objects.equals(userId, project.getUserId()))
        {
            log.error("项目生成配置校验失败: 项目不属于当前用户, projectId={}, projectUserId={}, userId={}",
                    projectId, project.getUserId(), userId);
            throw new ServiceException("无权限");
        }
        return project;
    }

    /**
     * 单场景配置项校验，返回待落库实体（未设审计字段）。
     * 保存为持久化偏好、不触发生成：智能体只校验「是该场景矩阵里的真实候选」（任意创作模式），
     * 不绑定当前项目创作模式，允许提前为暂不适用场景预设配置；生成时再由 resolve() 按当前模式把关。
     */
    private AidProjectGenConfig validateItem(Long projectId, Long userId, ProjectGenConfigItem item)
    {
        if (Objects.isNull(item))
        {
            log.error("项目生成配置校验失败: 配置项为空, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("配置异常");
        }
        // 场景编码合法性
        String sceneCode = StrUtil.trim(item.getSceneCode());
        ProjectGenConfigScene scene = ProjectGenConfigScene.fromCode(sceneCode);
        if (Objects.isNull(scene))
        {
            log.error("项目生成配置校验失败: 场景无效, projectId={}, sceneCode={}", projectId, item.getSceneCode());
            throw new ServiceException("场景不可用");
        }

        // 入参完整性
        String agentCode = StrUtil.trim(item.getAgentCode());
        String modelCode = StrUtil.trim(item.getModelCode());
        String resolution = StrUtil.trim(item.getResolution());
        String aspectRatio = StrUtil.trim(item.getAspectRatio());
        if (StrUtil.isBlank(agentCode) || StrUtil.isBlank(modelCode))
        {
            log.error("项目生成配置校验失败: 智能体或模型缺失, projectId={}, sceneCode={}, agentCode={}, modelCode={}",
                    projectId, sceneCode, agentCode, modelCode);
            throw new ServiceException("参数缺失");
        }
        if (scene.isNeedResolution() && StrUtil.isBlank(resolution))
        {
            log.error("项目生成配置校验失败: 清晰度缺失, projectId={}, sceneCode={}, modelCode={}",
                    projectId, sceneCode, modelCode);
            throw new ServiceException("参数缺失");
        }
        if (scene.isNeedAspectRatio() && StrUtil.isBlank(aspectRatio))
        {
            log.error("项目生成配置校验失败: 比例缺失, projectId={}, sceneCode={}, modelCode={}",
                    projectId, sceneCode, modelCode);
            throw new ServiceException("参数缺失");
        }

        // 智能体与场景业务分类匹配 + 启用状态校验
        AidAgent agent = aidAgentService.getByAgentCode(agentCode);
        if (Objects.isNull(agent))
        {
            log.error("项目生成配置校验失败: 智能体不存在或停用, projectId={}, sceneCode={}, agentCode={}",
                    projectId, sceneCode, agentCode);
            throw new ServiceException("智能体不可用");
        }
        // status=1 才视为启用；getByAgentCode 只过滤 del_flag，不看 status，此处补一道保险
        if (!Objects.equals(1, agent.getStatus()))
        {
            log.error("项目生成配置校验失败: 智能体已停用, projectId={}, sceneCode={}, agentCode={}, status={}",
                    projectId, sceneCode, agentCode, agent.getStatus());
            throw new ServiceException("智能体停用");
        }
        if (!Objects.equals(sceneCode, agent.getBizCategoryCode()))
        {
            log.error("项目生成配置校验失败: 智能体业务分类不符, projectId={}, sceneCode={}, agentCode={}, bizCategoryCode={}",
                    projectId, sceneCode, agentCode, agent.getBizCategoryCode());
            throw new ServiceException("智能体不匹配");
        }

        // 保存只是持久化偏好、不触发生成：不按「当前创作模式」可选池卡死，允许为暂不适用的场景
        // 提前预设配置（如 i2v 项目预存 multi 才用的视频提示词场景）。但仍要求该智能体是此场景矩阵里的
        // 真实候选（任意创作模式下挂过即可），防止存入越权/无效智能体。生成时再由 resolve() 按当前模式严格把关。
        if (!genAgentMatrixResolver.isAgentInScenePool(sceneCode, agentCode))
        {
            log.error("项目生成配置校验失败: 智能体不在该场景候选, projectId={}, sceneCode={}, agentCode={}",
                    projectId, sceneCode, agentCode);
            throw new ServiceException("暂不可用");
        }

        // 模型归属模型池（func_code = sceneCode）
        List<AiModelVO> pool = aiModelBusinessService.listAvailableModelsByFuncCode(sceneCode);
        if (CollectionUtil.isEmpty(pool))
        {
            log.error("项目生成配置校验失败: 模型池为空或未配置, projectId={}, sceneCode={}", projectId, sceneCode);
            throw new ServiceException("模型未配置");
        }
        boolean inPool = pool.stream().anyMatch(m -> Objects.equals(modelCode, m.getModelCode()));
        if (!inPool)
        {
            log.error("项目生成配置校验失败: 模型不在可选池内, projectId={}, sceneCode={}, modelCode={}, poolSize={}",
                    projectId, sceneCode, modelCode, pool.size());
            throw new ServiceException("模型不支持");
        }

        // 图片场景能力匹配（清晰度 / 比例命中模型 capability_json）
        if (scene.isImageScene())
        {
            validateImageCapability(projectId, scene, modelCode, resolution, aspectRatio);
        }

        // 组装实体（文字场景不写图片参数）
        AidProjectGenConfig entity = new AidProjectGenConfig();
        entity.setProjectId(projectId);
        entity.setUserId(userId);
        entity.setSceneCode(sceneCode);
        entity.setAgentCode(agentCode);
        entity.setModelCode(modelCode);
        entity.setResolution(scene.isNeedResolution() ? resolution : null);
        entity.setAspectRatio(scene.isNeedAspectRatio() ? aspectRatio : null);
        entity.setDelFlag(DEL_FLAG_NORMAL);
        return entity;
    }

    /**
     * 图片场景能力校验：清晰度命中 sizeOptions；分镜生图额外校验比例命中 aspectRatioOptions。
     */
    private void validateImageCapability(Long projectId, ProjectGenConfigScene scene, String modelCode,
                                         String resolution, String aspectRatio)
    {
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(modelConfig))
        {
            log.error("项目生成配置校验失败: 模型不存在, projectId={}, sceneCode={}, modelCode={}",
                    projectId, scene.getSceneCode(), modelCode);
            throw new ServiceException("模型不支持");
        }
        CapabilityVO capability = parseCapability(modelConfig.getCapabilityJson());

        // 清晰度
        if (scene.isNeedResolution() && !optionHit(resolution, capability.getSizeOptions()))
        {
            log.error("项目生成配置校验失败: 清晰度不在模型能力内, projectId={}, sceneCode={}, modelCode={}, resolution={}, sizeOptions={}",
                    projectId, scene.getSceneCode(), modelCode, resolution,
                    capability == null ? null : capability.getSizeOptions());
            throw new ServiceException("清晰度不支持");
        }
        // 比例（仅分镜生图）
        if (scene.isNeedAspectRatio() && !optionHit(aspectRatio, capability.getAspectRatioOptions()))
        {
            log.error("项目生成配置校验失败: 比例不在模型能力内, projectId={}, sceneCode={}, modelCode={}, aspectRatio={}, aspectRatioOptions={}",
                    projectId, scene.getSceneCode(), modelCode, aspectRatio,
                    capability == null ? null : capability.getAspectRatioOptions());
            throw new ServiceException("画面比例不符");
        }
    }

    /**
     * 解析 capability_json 为 CapabilityVO；空或失败抛"模型不符"。
     */
    private CapabilityVO parseCapability(String capabilityJson)
    {
        if (StrUtil.isBlank(capabilityJson))
        {
            log.error("项目生成配置校验失败: capabilityJson 为空");
            throw new ServiceException("模型不支持");
        }
        try
        {
            CapabilityVO capability = OBJECT_MAPPER.readValue(capabilityJson, CapabilityVO.class);
            if (Objects.isNull(capability))
            {
                log.error("项目生成配置校验失败: capabilityJson 解析为空");
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
            log.error("项目生成配置校验失败: capabilityJson 解析异常, err={}", e.getMessage());
            throw new ServiceException("模型不支持");
        }
    }

    /**
     * 候选项命中判断：去首尾空白 + 忽略大小写完全相等。
     */
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
    /**
     * 按 项目+用户+场景 upsert：存在则更新，不存在则新增。
     */
    private void upsert(AidProjectGenConfig entity, String operator)
    {
        AidProjectGenConfig existing = selectOne(entity.getProjectId(), entity.getUserId(), entity.getSceneCode());
        if (Objects.nonNull(existing))
        {
            // 更新：填更新时间与更新者
            existing.setAgentCode(entity.getAgentCode());
            existing.setModelCode(entity.getModelCode());
            existing.setResolution(entity.getResolution());
            existing.setAspectRatio(entity.getAspectRatio());
            existing.setUpdateBy(operator);
            existing.setUpdateTime(DateUtils.getNowDate());
            projectGenConfigMapper.updateById(existing);
            // 回填 id 便于返回
            entity.setId(existing.getId());
        }
        else
        {
            // 新增：填创建时间与创建者
            entity.setCreateBy(operator);
            entity.setCreateTime(DateUtils.getNowDate());
            entity.setUpdateBy(operator);
            entity.setUpdateTime(DateUtils.getNowDate());
            projectGenConfigMapper.insert(entity);
        }
    }

    /**
     * 按 项目+用户+场景 查询有效记录。
     */
    private AidProjectGenConfig selectOne(Long projectId, Long userId, String sceneCode)
    {
        LambdaQueryWrapper<AidProjectGenConfig> wrapper = Wrappers.<AidProjectGenConfig>lambdaQuery()
                .eq(AidProjectGenConfig::getProjectId, projectId)
                .eq(AidProjectGenConfig::getUserId, userId)
                .eq(AidProjectGenConfig::getSceneCode, sceneCode)
                .eq(AidProjectGenConfig::getDelFlag, DEL_FLAG_NORMAL)
                .last("limit 1");
        return projectGenConfigMapper.selectOne(wrapper);
    }
    /** 无任何配置时的占位 VO。 */
    private ProjectGenConfigVO noneVO(ProjectGenConfigScene scene)
    {
        return ProjectGenConfigVO.builder()
                .sceneCode(scene.getSceneCode())
                .source(SOURCE_NONE)
                .build();
    }
    private ProjectGenConfigVO toVO(AidProjectGenConfig entity, String source)
    {
        return ProjectGenConfigVO.builder()
                .sceneCode(entity.getSceneCode())
                .agentCode(entity.getAgentCode())
                .modelCode(entity.getModelCode())
                .resolution(entity.getResolution())
                .aspectRatio(entity.getAspectRatio())
                .source(source)
                .build();
    }

    /**
     * 当前操作者用户名，取不到时回退用户ID字符串。
     */
    private String currentOperator(Long userId)
    {
        try
        {
            String username = SecurityUtils.getUsername();
            return StrUtil.isBlank(username) ? String.valueOf(userId) : username;
        }
        catch (Exception e)
        {
            return String.valueOf(userId);
        }
    }
}
