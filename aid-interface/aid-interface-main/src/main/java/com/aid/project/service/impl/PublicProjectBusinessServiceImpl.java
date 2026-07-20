package com.aid.project.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.vo.AidPublicProjectVo;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidEpisodeEditorService;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.exception.ServiceException;
import com.aid.core.service.ISysUserService;
import com.aid.project.dto.PublicVideoQueryRequest;
import com.aid.project.service.IPublicProjectBusinessService;
import com.aid.project.vo.PublicEpisodeVO;
import com.aid.project.vo.PublicProjectDetailVO;
import com.aid.project.vo.PublicVideoVO;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 公开项目业务Service实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class PublicProjectBusinessServiceImpl implements IPublicProjectBusinessService
{
    /** 是否公开：是 */
    private static final String IS_PUBLIC_YES = "1";

    /** 项目状态：审核中（已公开项目重新导出后再提审，审核期间旧成片继续展示） */
    private static final Integer PROJECT_STATUS_AUDITING = 3;

    /** 项目状态：审核通过 */
    private static final Integer PROJECT_STATUS_APPROVED = 4;

    /** 剧集状态：审核中（曾过审剧集重新提审期间旧成片继续展示） */
    private static final Integer EPISODE_STATUS_AUDITING = 3;

    /** 剧集状态：审核通过 */
    private static final Integer EPISODE_STATUS_APPROVED = 4;

    /** 删除标志：正常（未删除） */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 项目类型：电影 */
    private static final String PROJECT_TYPE_MOVIE = "movie";

    /** 项目类型：剧集 */
    private static final String PROJECT_TYPE_SERIES = "series";

    /** 电影的剧集ID标识 */
    private static final Long MOVIE_EPISODE_ID = 0L;

    @Autowired
    private IAidComicProjectService aidComicProjectService;

    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;

    @Autowired
    private IAidEpisodeEditorService aidEpisodeEditorService;

    @Autowired
    private ISysUserService sysUserService;

    /**
     * 查询公开广场项目列表（电影+剧集，含作者昵称/类型/介绍/发布时间）
     *
     * @param request 查询请求（可选projectName模糊搜索、projectType类型筛选）
     * @return 分页结果（total总数 + data公开视频VO列表）
     */
    @Override
    public AjaxResult listPublicVideo(PublicVideoQueryRequest request)
    {
        // 开启分页（紧邻联表查询，确保 PageHelper 仅拦截这一条；分页参数取自 POST 请求体）
        PageHelper.startPage(request.resolvePageNum(), request.resolvePageSize());
        // 联表查询公开项目并带出作者昵称；
        // 放行「审核中(3)」：已公开项目重新提审时新片走待审槽，final_video_url 仍是过审旧片，
        // 审核期间旧片继续对外展示，is_public=1 只可能在曾过审后置位，不会漏出未过审内容
        List<AidPublicProjectVo> projectList = aidComicProjectService.selectPublicProjectVoList(
                request.getProjectName(), request.getProjectType());
        // 先基于分页代理对象取总数，再组装 VO，避免转换后丢失分页信息
        long total = new PageInfo<>(projectList).getTotal();

        if (CollectionUtil.isEmpty(projectList))
        {
            return buildPageResult(total, new ArrayList<>());
        }

        // 电影项目：批量查项目级成片（episodeId=0）；审核中项目须存在待审新片（final 槽为曾过审旧片）才展示
        List<AidPublicProjectVo> movieProjects = projectList.stream()
                .filter(project -> Objects.equals(project.getProjectType(), PROJECT_TYPE_MOVIE))
                .collect(Collectors.toList());
        Map<Long, String> movieVideoMap = queryMovieVideoMap(movieProjects);

        // 剧集项目：批量查已过审剧集，统计可播集数并取第一集成片作为列表预览
        List<Long> seriesIds = filterProjectIdsByType(projectList, PROJECT_TYPE_SERIES);
        Map<Long, List<PublicEpisodeVO>> seriesEpisodeMap = queryPublicEpisodesByProjects(seriesIds);

        List<PublicVideoVO> voList = projectList.stream()
                .map(project -> buildPublicVideoVo(project, movieVideoMap, seriesEpisodeMap))
                .collect(Collectors.toList());
        return buildPageResult(total, voList);
    }

    /**
     * 查询公开项目详情（剧集类型返回剧集列表供切换播放）
     *
     * @param projectId 项目ID
     * @return 公开项目详情VO
     */
    @Override
    public PublicProjectDetailVO getPublicProjectDetail(Long projectId)
    {
        // 查询项目并校验
        AidComicProject project = getAndValidatePublicProject(projectId);

        // 作者昵称（作者账号被删除时置空，不阻断详情展示）
        SysUser author = sysUserService.selectUserById(project.getUserId());
        String authorNickname = Objects.isNull(author) ? null : author.getNickName();

        PublicProjectDetailVO.PublicProjectDetailVOBuilder builder = PublicProjectDetailVO.builder()
                .id(project.getId())
                .projectName(project.getProjectName())
                .authorNickname(authorNickname)
                .projectType(project.getProjectType())
                .coverUrl(project.getCoverUrl())
                .publishTime(project.getPublishTime())
                .updateTime(project.getUpdateTime())
                .projectDesc(project.getProjectDesc())
                .videoStyleType(project.getVideoStyleType());

        if (Objects.equals(project.getProjectType(), PROJECT_TYPE_SERIES))
        {
            // 剧集类型：返回可播剧集列表，默认播放第一集成片
            List<PublicEpisodeVO> episodes = queryPublicEpisodesByProjects(List.of(projectId))
                    .getOrDefault(projectId, new ArrayList<>());
            builder.episodes(episodes);
            builder.episodeCount(episodes.size());
            builder.finalVideoUrl(episodes.isEmpty() ? null : episodes.get(0).getVideoUrl());
        }
        else
        {
            // 电影类型：取项目级成片（episodeId=0），审核中项目须存在待审新片才展示旧成片
            builder.finalVideoUrl(getFinalVideoUrl(projectId, MOVIE_EPISODE_ID, project.getStatus()));
        }
        return builder.build();
    }

    /**
     * 组装分页返回结构（total + data）
     *
     * @param total  总条数
     * @param voList 当前页数据
     * @return AjaxResult
     */
    private AjaxResult buildPageResult(long total, List<PublicVideoVO> voList)
    {
        AjaxResult result = AjaxResult.success();
        result.put("total", total);
        result.put("data", voList);
        return result;
    }

    /**
     * 按项目类型筛选项目ID
     *
     * @param projectList 项目列表
     * @param projectType 项目类型
     * @return 项目ID列表
     */
    private List<Long> filterProjectIdsByType(List<AidPublicProjectVo> projectList, String projectType)
    {
        return projectList.stream()
                .filter(project -> Objects.equals(project.getProjectType(), projectType))
                .map(AidPublicProjectVo::getId)
                .collect(Collectors.toList());
    }

    /**
     * 组装单条公开广场VO（电影取项目成片，剧集取第一集成片并带集数）
     *
     * @param project          公开项目
     * @param movieVideoMap    电影成片映射（projectId → 视频地址）
     * @param seriesEpisodeMap 剧集可播列表映射（projectId → 剧集VO列表）
     * @return 公开视频VO
     */
    private PublicVideoVO buildPublicVideoVo(AidPublicProjectVo project, Map<Long, String> movieVideoMap,
                                             Map<Long, List<PublicEpisodeVO>> seriesEpisodeMap)
    {
        PublicVideoVO.PublicVideoVOBuilder builder = PublicVideoVO.builder()
                .id(project.getId())
                .projectName(project.getProjectName())
                .authorNickname(project.getAuthorNickname())
                .projectType(project.getProjectType())
                .projectDesc(project.getProjectDesc())
                .publishTime(project.getPublishTime())
                .coverUrl(project.getCoverUrl());
        if (Objects.equals(project.getProjectType(), PROJECT_TYPE_SERIES))
        {
            // 剧集：集数=可播剧集数，预览视频=第一集成片
            List<PublicEpisodeVO> episodes = seriesEpisodeMap.getOrDefault(project.getId(), new ArrayList<>());
            builder.episodeCount(episodes.size());
            builder.finalVideoUrl(episodes.isEmpty() ? null : episodes.get(0).getVideoUrl());
        }
        else
        {
            builder.finalVideoUrl(movieVideoMap.get(project.getId()));
        }
        return builder.build();
    }

    /**
     * 批量查询电影项目成片映射（episodeId=0）。
     * 防漏出未过审内容：审核中(3)的项目仅当剪辑记录存在待审新片（pending_video_url 非空，
     * 说明 final 槽是曾过审旧片）时才纳入；首次提审未过审的项目 final 槽为首次导出成片，不对外展示。
     *
     * @param movieProjects 电影项目列表（含状态）
     * @return projectId → 成片地址映射
     */
    private Map<Long, String> queryMovieVideoMap(List<AidPublicProjectVo> movieProjects)
    {
        Map<Long, String> videoMap = new HashMap<>();
        if (CollectionUtil.isEmpty(movieProjects))
        {
            return videoMap;
        }
        // 手动装映射：Collectors.toMap 不接受 null value，status 缺失时按审核中处理（保守不展示）
        Map<Long, Integer> statusById = new HashMap<>();
        for (AidPublicProjectVo project : movieProjects)
        {
            statusById.put(project.getId(),
                    Objects.isNull(project.getStatus()) ? PROJECT_STATUS_AUDITING : project.getStatus());
        }
        // 精简查询字段：projectId + 成片地址 + 待审槽（审核中项目的曾过审判定依据）
        LambdaQueryWrapper<AidEpisodeEditor> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidEpisodeEditor::getProjectId, AidEpisodeEditor::getFinalVideoUrl,
                AidEpisodeEditor::getPendingVideoUrl);
        wrapper.in(AidEpisodeEditor::getProjectId, statusById.keySet());
        wrapper.eq(AidEpisodeEditor::getEpisodeId, MOVIE_EPISODE_ID);
        wrapper.eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL);
        List<AidEpisodeEditor> editorList = aidEpisodeEditorService.list(wrapper);
        for (AidEpisodeEditor editor : editorList)
        {
            if (StrUtil.isBlank(editor.getFinalVideoUrl()) || videoMap.containsKey(editor.getProjectId()))
            {
                continue;
            }
            // 审核中且无待审新片 = 首次提审，final 槽是未过审成片，不对外展示
            if (Objects.equals(statusById.get(editor.getProjectId()), PROJECT_STATUS_AUDITING)
                    && StrUtil.isBlank(editor.getPendingVideoUrl()))
            {
                continue;
            }
            videoMap.put(editor.getProjectId(), editor.getFinalVideoUrl());
        }
        return videoMap;
    }

    /**
     * 批量查询剧集项目的可播剧集列表（曾过审且有成片的剧集，按集数升序）。
     * 审核通过(4)的剧集直接纳入；审核中(3)的剧集仅当存在待审新片（pending_video_url 非空，
     * 说明 final 槽是曾过审旧片）时才纳入——首次提审未过审的剧集 final 槽可能已有首次导出成片，
     * 必须排除，防止未审核内容漏出公开广场。
     *
     * @param seriesIds 剧集项目ID列表
     * @return projectId → 可播剧集VO列表（按集数升序）
     */
    private Map<Long, List<PublicEpisodeVO>> queryPublicEpisodesByProjects(List<Long> seriesIds)
    {
        Map<Long, List<PublicEpisodeVO>> resultMap = new LinkedHashMap<>();
        if (CollectionUtil.isEmpty(seriesIds))
        {
            return resultMap;
        }
        // 精简查询字段：仅取剧集展示必要字段 + 状态（曾过审判定依据）
        LambdaQueryWrapper<AidComicEpisode> episodeWrapper = Wrappers.lambdaQuery();
        episodeWrapper.select(AidComicEpisode::getId, AidComicEpisode::getProjectId, AidComicEpisode::getStatus,
                AidComicEpisode::getEpisodeNo, AidComicEpisode::getComicTitle, AidComicEpisode::getComicCoverUrl);
        episodeWrapper.in(AidComicEpisode::getProjectId, seriesIds);
        episodeWrapper.in(AidComicEpisode::getStatus, EPISODE_STATUS_AUDITING, EPISODE_STATUS_APPROVED);
        episodeWrapper.eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL);
        episodeWrapper.orderByAsc(AidComicEpisode::getEpisodeNo);
        List<AidComicEpisode> episodes = aidComicEpisodeService.list(episodeWrapper);
        if (CollectionUtil.isEmpty(episodes))
        {
            return resultMap;
        }

        // 批量查各剧集剪辑记录（剧集ID全局唯一，按 episodeId 建映射；含待审槽用于曾过审判定）
        List<Long> episodeIds = episodes.stream().map(AidComicEpisode::getId).collect(Collectors.toList());
        Map<Long, AidEpisodeEditor> editorMap = queryEpisodeEditorMap(episodeIds);

        for (AidComicEpisode episode : episodes)
        {
            AidEpisodeEditor editor = editorMap.get(episode.getId());
            String videoUrl = Objects.isNull(editor) ? null : editor.getFinalVideoUrl();
            if (StrUtil.isBlank(videoUrl))
            {
                continue;
            }
            // 审核中且无待审新片 = 首次提审，final 槽是未过审成片，不对外展示
            if (Objects.equals(episode.getStatus(), EPISODE_STATUS_AUDITING)
                    && StrUtil.isBlank(editor.getPendingVideoUrl()))
            {
                continue;
            }
            resultMap.computeIfAbsent(episode.getProjectId(), key -> new ArrayList<>())
                    .add(PublicEpisodeVO.builder()
                            .episodeId(episode.getId())
                            .episodeNo(episode.getEpisodeNo())
                            .title(episode.getComicTitle())
                            .coverUrl(episode.getComicCoverUrl())
                            .videoUrl(videoUrl)
                            .build());
        }
        return resultMap;
    }

    /**
     * 批量查询剧集剪辑记录映射（成片地址 + 待审槽）。
     *
     * @param episodeIds 剧集ID列表
     * @return episodeId → 剪辑记录映射（仅含成片非空的记录）
     */
    private Map<Long, AidEpisodeEditor> queryEpisodeEditorMap(List<Long> episodeIds)
    {
        if (CollectionUtil.isEmpty(episodeIds))
        {
            return new HashMap<>();
        }
        // 精简查询字段：episodeId + 成片地址 + 待审槽（曾过审判定依据）
        LambdaQueryWrapper<AidEpisodeEditor> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidEpisodeEditor::getEpisodeId, AidEpisodeEditor::getFinalVideoUrl,
                AidEpisodeEditor::getPendingVideoUrl);
        wrapper.in(AidEpisodeEditor::getEpisodeId, episodeIds);
        wrapper.eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL);
        List<AidEpisodeEditor> editorList = aidEpisodeEditorService.list(wrapper);
        return editorList.stream()
                .filter(editor -> StrUtil.isNotBlank(editor.getFinalVideoUrl()))
                .collect(Collectors.toMap(AidEpisodeEditor::getEpisodeId, editor -> editor,
                        (existing, replacement) -> existing));
    }

    /**
     * 查询并校验公开项目
     * 校验项目是否存在、是否公开、是否审核通过
     *
     * @param projectId 项目ID
     * @return 项目实体
     */
    private AidComicProject getAndValidatePublicProject(Long projectId)
    {
        LambdaQueryWrapper<AidComicProject> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidComicProject::getId, projectId);
        wrapper.eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL);
        AidComicProject project = aidComicProjectService.getOne(wrapper);

        if (Objects.isNull(project))
        {
            log.info("公开项目查询失败：项目不存在, projectId={}", projectId);
            throw new ServiceException("项目不存在");
        }
        if (!Objects.equals(project.getIsPublic(), IS_PUBLIC_YES))
        {
            log.info("公开项目查询失败：项目未公开, projectId={}", projectId);
            throw new ServiceException("项目未公开");
        }
        // 审核中(3)放行：已公开项目重新提审期间旧成片继续展示（与列表口径一致）
        if (!Objects.equals(project.getStatus(), PROJECT_STATUS_APPROVED)
                && !Objects.equals(project.getStatus(), PROJECT_STATUS_AUDITING))
        {
            log.info("公开项目查询失败：项目未审核通过, projectId={}, status={}", projectId, project.getStatus());
            throw new ServiceException("项目未审核");
        }
        return project;
    }

    /**
     * 查询公开可播的成片视频地址：审核中(3)的内容仅当存在待审新片（final 槽为曾过审旧片）时返回。
     * 查询字段精简：仅取成片地址与待审槽；同键多行时取最新一条（与受理侧口径一致）。
     *
     * @param projectId     项目ID
     * @param episodeId     剧集ID（电影传0）
     * @param projectStatus 项目状态
     * @return 成片视频地址，不存在或未曾过审返回null
     */
    private String getFinalVideoUrl(Long projectId, Long episodeId, Integer projectStatus)
    {
        LambdaQueryWrapper<AidEpisodeEditor> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidEpisodeEditor::getId, AidEpisodeEditor::getFinalVideoUrl,
                AidEpisodeEditor::getPendingVideoUrl);
        wrapper.eq(AidEpisodeEditor::getProjectId, projectId);
        wrapper.eq(AidEpisodeEditor::getEpisodeId, episodeId);
        wrapper.eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.orderByDesc(AidEpisodeEditor::getId);
        wrapper.last("LIMIT 1");
        AidEpisodeEditor editor = aidEpisodeEditorService.getOne(wrapper);
        if (Objects.isNull(editor))
        {
            return null;
        }
        // 审核中且无待审新片 = 首次提审，final 槽是未过审成片，不对外展示
        if (Objects.equals(projectStatus, PROJECT_STATUS_AUDITING)
                && StrUtil.isBlank(editor.getPendingVideoUrl()))
        {
            return null;
        }
        return editor.getFinalVideoUrl();
    }
}
