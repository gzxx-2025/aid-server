package com.aid.agent.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.agent.IAidAgentService;
import com.aid.agent.dto.AgentQueryRequest;
import com.aid.agent.vo.AgentInfoVO;
import com.aid.agent.vo.AgentListGroupVO;
import com.aid.aid.domain.AidAgent;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.mapper.AidAgentMapper;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.SecurityUtils;
import com.aid.projectgenconfig.matrix.IGenAgentMatrixResolver;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 智能体业务 Service 实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AidAgentServiceImpl extends ServiceImpl<AidAgentMapper, AidAgent> implements IAidAgentService
{
    /** 启用状态 */
    private static final int STATUS_ENABLED = 1;

    /** 软删标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 项目类型：电影（取项目 default_creation_mode；其它=剧集，取剧集 creation_mode） */
    private static final String PROJECT_TYPE_MOVIE = "movie";

    /** 智能体可选池矩阵解析器（按创作模式裁剪 /aid/agent/list 候选）；@Lazy 防潜在循环依赖 */
    @Lazy
    @Autowired
    private IGenAgentMatrixResolver genAgentMatrixResolver;

    @Lazy
    @Autowired
    private IAidComicProjectService aidComicProjectService;

    @Lazy
    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;

    @Override
    public AidAgent getByAgentCode(String agentCode)
    {
        if (StrUtil.isBlank(agentCode))
        {
            return null;
        }
        // 校验性查询：取主键 + 业务必要字段
        LambdaQueryWrapper<AidAgent> wrapper = Wrappers.<AidAgent>lambdaQuery()
                .eq(AidAgent::getAgentCode, agentCode)
                .eq(AidAgent::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1");
        return this.getOne(wrapper, false);
    }

    @Override
    public AidAgent getByAgentCodeAndAssertBizCategory(String agentCode, String expectedBizCategoryCode)
    {
        if (StrUtil.isBlank(agentCode))
        {
            log.error("智能体业务分类断言失败: agentCode 为空, expectedBizCategoryCode={}", expectedBizCategoryCode);
            throw new RuntimeException("编码为空");
        }
        AidAgent agent = getByAgentCode(agentCode);
        if (Objects.isNull(agent))
        {
            log.error("智能体业务分类断言失败: 智能体不存在, agentCode={}", agentCode);
            throw new RuntimeException("智能体不存在");
        }
        if (!Objects.equals(STATUS_ENABLED, agent.getStatus()))
        {
            log.error("智能体业务分类断言失败: 智能体已停用, agentCode={}, status={}", agentCode, agent.getStatus());
            throw new RuntimeException("智能体停用");
        }
        if (!Objects.equals(expectedBizCategoryCode, agent.getBizCategoryCode()))
        {
            log.error("智能体业务分类断言失败: 分类不匹配, agentCode={}, expected={}, actual={}",
                    agentCode, expectedBizCategoryCode, agent.getBizCategoryCode());
            throw new RuntimeException("类型不匹配");
        }
        return agent;
    }

    @Override
    public List<AidAgent> listAgents(AgentQueryRequest query)
    {
        LambdaQueryWrapper<AidAgent> wrapper = Wrappers.<AidAgent>lambdaQuery()
                .eq(AidAgent::getDelFlag, DEL_FLAG_NORMAL);
        if (Objects.nonNull(query))
        {
            // 业务分类编码过滤
            if (StrUtil.isNotBlank(query.getBizCategoryCode()))
            {
                wrapper.eq(AidAgent::getBizCategoryCode, query.getBizCategoryCode());
            }
            // 名称模糊查询
            if (StrUtil.isNotBlank(query.getName()))
            {
                wrapper.like(AidAgent::getName, query.getName());
            }
            // 智能体编码精确匹配
            if (StrUtil.isNotBlank(query.getAgentCode()))
            {
                wrapper.eq(AidAgent::getAgentCode, query.getAgentCode());
            }
            // 状态过滤
            if (Objects.nonNull(query.getStatus()))
            {
                wrapper.eq(AidAgent::getStatus, query.getStatus());
            }
        }
        wrapper.orderByDesc(AidAgent::getId);
        return this.list(wrapper);
    }

    @Override
    public List<AgentListGroupVO> listEnabledAgentsGroupedForClient(List<String> bizCategoryCodes)
    {
        // 不带项目维度的重载：不按创作模式裁剪
        return listEnabledAgentsGroupedForClient(bizCategoryCodes, null, null, null);
    }

    @Override
    public List<AgentListGroupVO> listEnabledAgentsGroupedForClient(List<String> bizCategoryCodes,
                                                                    Long projectId, Long episodeId, Long userId)
    {
        // 入参去重并保持入参顺序（用于显式查询时的占位分组顺序）
        List<String> distinctCodes = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(bizCategoryCodes))
        {
            for (String code : bizCategoryCodes)
            {
                if (StrUtil.isNotBlank(code) && !distinctCodes.contains(code))
                {
                    distinctCodes.add(code);
                }
            }
        }

        // C 端列表：启用 + 未删除 + 业务分类可选过滤；只查 C 端 VO 必要字段，绝不返回 prompt_content
        LambdaQueryWrapper<AidAgent> wrapper = Wrappers.<AidAgent>lambdaQuery()
                .select(AidAgent::getId, AidAgent::getAgentCode, AidAgent::getName, AidAgent::getIconUrl,
                        AidAgent::getSubTitle, AidAgent::getIntroduction, AidAgent::getModelCode,
                        AidAgent::getBizCategoryCode, AidAgent::getStatus)
                .eq(AidAgent::getDelFlag, DEL_FLAG_NORMAL)
                .eq(AidAgent::getStatus, STATUS_ENABLED);
        if (CollectionUtil.isNotEmpty(distinctCodes))
        {
            wrapper.in(AidAgent::getBizCategoryCode, distinctCodes);
        }
        wrapper.orderByAsc(AidAgent::getId);
        List<AidAgent> rows = this.list(wrapper);

        List<AgentListGroupVO> result;
        if (CollectionUtil.isNotEmpty(distinctCodes))
        {
            // 显式传入：严格按入参顺序输出每个分组（无匹配则 agents 为空数组占位）
            Map<String, List<AgentInfoVO>> bucket = new LinkedHashMap<>();
            for (String code : distinctCodes)
            {
                bucket.put(code, new ArrayList<>());
            }
            if (CollectionUtil.isNotEmpty(rows))
            {
                for (AidAgent row : rows)
                {
                    List<AgentInfoVO> list = bucket.get(row.getBizCategoryCode());
                    if (list != null)
                    {
                        list.add(toClientVO(row));
                    }
                }
            }
            result = new ArrayList<>(bucket.size());
            for (Map.Entry<String, List<AgentInfoVO>> entry : bucket.entrySet())
            {
                AgentListGroupVO group = new AgentListGroupVO();
                group.setBizCategoryCode(entry.getKey());
                group.setAgents(entry.getValue());
                result.add(group);
            }
        }
        else if (CollectionUtil.isEmpty(rows))
        {
            // 未传过滤条件且无数据
            result = new ArrayList<>();
        }
        else
        {
            // 未传过滤条件：返回全部启用智能体，按其各自的 bizCategoryCode 分组（按首次出现顺序，bizCategoryCode 为空归入 null 分组）
            Map<String, List<AgentInfoVO>> bucket = new LinkedHashMap<>();
            for (AidAgent row : rows)
            {
                String key = StrUtil.isBlank(row.getBizCategoryCode()) ? null : row.getBizCategoryCode();
                bucket.computeIfAbsent(key, k -> new ArrayList<>()).add(toClientVO(row));
            }
            result = new ArrayList<>(bucket.size());
            for (Map.Entry<String, List<AgentInfoVO>> entry : bucket.entrySet())
            {
                AgentListGroupVO group = new AgentListGroupVO();
                group.setBizCategoryCode(entry.getKey());
                group.setAgents(entry.getValue());
                result.add(group);
            }
        }

        // 按项目创作模式裁剪：受矩阵管理的场景仅保留当前创作模式可选池内的智能体
        filterByProjectCreationMode(result, projectId, episodeId, userId);
        return result;
    }

    /**
     * 按项目创作模式对分组结果裁剪：对「受矩阵管理的场景」仅保留当前创作模式可选池内的智能体，
     * 且按可选池顺序（sortOrder）重排，与 {@code /get} 的 agentOptions 顺序一致；非矩阵管理的分类不动。
     * projectId 为空 / 创作模式解析为空时不裁剪。一次批量查矩阵表，避免 N+1。
     */
    private void filterByProjectCreationMode(List<AgentListGroupVO> groups, Long projectId, Long episodeId, Long userId)
    {
        if (Objects.isNull(projectId) || CollectionUtil.isEmpty(groups))
        {
            return;
        }
        String creationMode = resolveProjectCreationMode(projectId, episodeId, userId);
        if (StrUtil.isBlank(creationMode))
        {
            // 项目缺失 / 无权限 / 创作模式未设：不裁剪，保持全量，避免误伤
            log.info("智能体列表按创作模式过滤跳过: 创作模式为空, projectId={}, episodeId={}", projectId, episodeId);
            return;
        }
        // 收集本次全部非空业务分类，一次性批量解析「受管场景 → 当前模式可选池」
        List<String> bizCodes = new ArrayList<>();
        for (AgentListGroupVO group : groups)
        {
            if (StrUtil.isNotBlank(group.getBizCategoryCode()))
            {
                bizCodes.add(group.getBizCategoryCode());
            }
        }
        Map<String, List<String>> managedPools = genAgentMatrixResolver.listManagedScenePools(bizCodes, creationMode);
        for (AgentListGroupVO group : groups)
        {
            String biz = group.getBizCategoryCode();
            // 未受矩阵管理（非生成链路场景）→ 保持全量
            if (StrUtil.isBlank(biz) || !managedPools.containsKey(biz))
            {
                continue;
            }
            // 受管：按可选池顺序重排并裁剪（池为空=当前模式不适用→清空）
            List<String> pool = managedPools.get(biz);
            List<AgentInfoVO> kept = new ArrayList<>();
            if (CollectionUtil.isNotEmpty(pool) && CollectionUtil.isNotEmpty(group.getAgents()))
            {
                // 先按 agentCode 建索引，再按池顺序拣选，保证顺序与 /get agentOptions 一致
                Map<String, AgentInfoVO> byCode = new HashMap<>();
                for (AgentInfoVO a : group.getAgents())
                {
                    byCode.put(a.getAgentCode(), a);
                }
                for (String code : pool)
                {
                    AgentInfoVO a = byCode.get(code);
                    if (Objects.nonNull(a))
                    {
                        kept.add(a);
                    }
                }
            }
            group.setAgents(kept);
        }
    }

    /**
     * 解析项目创作模式：电影=项目 default_creation_mode；剧集=对应剧集 creation_mode（带归属校验），
     * 剧集无效/未传则回退项目默认。项目不存在 / 不属于当前用户返回空串（上层据此回退全量、不裁剪）。
     */
    private String resolveProjectCreationMode(Long projectId, Long episodeId, Long userId)
    {
        // 归属校验：按 id + userId 取项目（防用他人项目的创作模式裁剪自己的列表）
        AidComicProject project = aidComicProjectService.getOne(
                Wrappers.<AidComicProject>lambdaQuery()
                        .select(AidComicProject::getId, AidComicProject::getProjectType,
                                AidComicProject::getDefaultCreationMode)
                        .eq(AidComicProject::getId, projectId)
                        .eq(AidComicProject::getUserId, userId)
                        .last("limit 1"));
        if (Objects.isNull(project))
        {
            return "";
        }
        // 剧集类项目 + 指定剧集：取剧集创作模式（带归属校验，防越权）
        if (!Objects.equals(PROJECT_TYPE_MOVIE, project.getProjectType()) && Objects.nonNull(episodeId))
        {
            AidComicEpisode episode = aidComicEpisodeService.getOne(
                    Wrappers.<AidComicEpisode>lambdaQuery()
                            .select(AidComicEpisode::getId, AidComicEpisode::getCreationMode)
                            .eq(AidComicEpisode::getId, episodeId)
                            .eq(AidComicEpisode::getProjectId, projectId)
                            .eq(AidComicEpisode::getUserId, userId)
                            .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL)
                            .last("limit 1"));
            if (Objects.nonNull(episode) && StrUtil.isNotBlank(episode.getCreationMode()))
            {
                return episode.getCreationMode().trim();
            }
        }
        return StrUtil.trimToEmpty(project.getDefaultCreationMode());
    }

    @Override
    public int insertAgent(AidAgent agent)
    {
        // 创建：必须填写创建时间和创建者
        String currentUser = currentUserName();
        agent.setCreateBy(currentUser);
        agent.setCreateTime(DateUtils.getNowDate());
        agent.setUpdateBy(currentUser);
        agent.setUpdateTime(DateUtils.getNowDate());
        if (Objects.isNull(agent.getStatus()))
        {
            agent.setStatus(STATUS_ENABLED);
        }
        if (StrUtil.isBlank(agent.getDelFlag()))
        {
            agent.setDelFlag(DEL_FLAG_NORMAL);
        }
        return this.save(agent) ? 1 : 0;
    }

    @Override
    public int updateAgent(AidAgent agent)
    {
        // 更新：必须填写更新时间和更新者
        agent.setUpdateBy(currentUserName());
        agent.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(agent) ? 1 : 0;
    }

    @Override
    public int deleteAgentById(Long id)
    {
        return this.removeById(id) ? 1 : 0;
    }

    @Override
    public AgentInfoVO toClientVO(AidAgent agent)
    {
        if (Objects.isNull(agent))
        {
            return null;
        }
        AgentInfoVO vo = new AgentInfoVO();
        vo.setId(agent.getId());
        vo.setAgentCode(agent.getAgentCode());
        vo.setName(agent.getName());
        vo.setIconUrl(agent.getIconUrl()); // 智能体图标地址，C 端下拉/展示用
        vo.setSubTitle(agent.getSubTitle());
        vo.setIntroduction(agent.getIntroduction());
        vo.setModelCode(agent.getModelCode());
        vo.setBizCategoryCode(agent.getBizCategoryCode());
        vo.setStatus(agent.getStatus());
        return vo;
    }

    /** 取当前用户名，未登录场景兜底为 system */
    private String currentUserName()
    {
        try
        {
            String name = SecurityUtils.getUsername();
            return StrUtil.blankToDefault(name, "system");
        }
        catch (Exception ignore)
        {
            return "system";
        }
    }
}
