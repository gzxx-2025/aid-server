package com.aid.episode.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.domain.AidScenePlot;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidComicScriptService;
import com.aid.aid.service.IAidEpisodeEditorService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.aid.service.IAidScenePlotService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.exception.ServiceException;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.page.SafePageUtils;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.StringUtils;
import com.aid.compose.ComposeConstants;
import com.aid.enums.CreationModeEnum;
import com.aid.enums.EpisodeStatusEnum;
import com.aid.enums.GenModeEnum;
import com.aid.enums.ProjectTypeEnum;
import com.aid.episode.dto.UserEpisodeCreateRequest;
import com.aid.episode.dto.UserEpisodeQueryRequest;
import com.aid.episode.dto.UserEpisodeUpdateRequest;
import com.aid.episode.service.IUserEpisodeBusinessService;
import com.aid.episode.vo.UserEpisodeVO;
import com.aid.projectgenconfig.service.IProjectGenConfigService;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户剧集业务Service实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class UserEpisodeBusinessServiceImpl implements IUserEpisodeBusinessService
{
    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;

    @Autowired
    private IAidComicProjectService aidComicProjectService;

    @Autowired
    private IAidEpisodeEditorService aidEpisodeEditorService;

    @Autowired
    private MediaUrlResolver mediaUrlResolver;

    @Autowired
    private IAidStoryboardService aidStoryboardService;

    @Autowired
    private IProjectGenConfigService projectGenConfigService;

    /** 剧本服务：删剧集时级联删除该集全部剧本版本 */
    @Autowired
    private IAidComicScriptService aidComicScriptService;

    /** 场次服务：删剧集时级联删除该集场次 */
    @Autowired
    private IAidScenePlotService aidScenePlotService;

    /** 角色音色绑定服务：删剧集时级联删除该集绑定 */
    @Autowired
    private IAidRoleVoiceBindingService aidRoleVoiceBindingService;

    /** 生成产物级联清理服务：删剧集时级联硬删其下 gen_record / audio_record 并清 OSS */
    @Autowired
    private com.aid.media.cleanup.IGenerationArtifactCleanupService generationArtifactCleanupService;

    /** OSS 文件清理服务：清理剧集封面、成片视频等 OSS 文件 */
    @Autowired
    private com.aid.media.cleanup.IMediaOssCleanupService mediaOssCleanupService;

    /**
     * 校验项目归属并返回项目
     */
    private AidComicProject getAndCheckProject(Long projectId, Long userId)
    {
        LambdaQueryWrapper<AidComicProject> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidComicProject::getId, projectId);
        wrapper.eq(AidComicProject::getUserId, userId);
        wrapper.eq(AidComicProject::getDelFlag, "0");
        AidComicProject project = aidComicProjectService.getOne(wrapper);
        if (project == null) {
            log.info("剧集操作项目缺失或越权, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("项目不存在");
        }
        return project;
    }

    /**
     * 校验剧集枚举字段
     */
    private void validateEnumFields(String genMode, String creationMode)
    {
        if (StringUtils.isNotEmpty(genMode) && GenModeEnum.getByValue(genMode) == null) {
            throw new RuntimeException("生成模式参数错误");
        }
        if (StringUtils.isNotEmpty(creationMode) && CreationModeEnum.getByValue(creationMode) == null) {
            throw new RuntimeException("创作模式参数错误");
        }
    }

    @Override
    public List<AidComicEpisode> selectUserEpisodeList(UserEpisodeQueryRequest request, Long userId)
    {
        // 先校验项目归属
        getAndCheckProject(request.getProjectId(), userId);

        LambdaQueryWrapper<AidComicEpisode> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidComicEpisode::getProjectId, request.getProjectId());
        wrapper.eq(AidComicEpisode::getUserId, userId);
        wrapper.eq(AidComicEpisode::getDelFlag, "0");
        if (StringUtils.isNotEmpty(request.getComicTitle())) {
            wrapper.like(AidComicEpisode::getComicTitle, request.getComicTitle());
        }
        if (request.getStatus() != null) {
            wrapper.eq(AidComicEpisode::getStatus, request.getStatus());
        }
        wrapper.orderByAsc(AidComicEpisode::getEpisodeNo);
        // 分页紧邻列表查询开启（归属校验查询在前会消费分页拦截，导致列表退化为全量）
        SafePageUtils.startClampedPage();
        return aidComicEpisodeService.list(wrapper);
    }

    @Override
    public AidComicEpisode selectUserEpisodeById(Long id, Long userId)
    {
        AidComicEpisode episode = aidComicEpisodeService.getOne(
                Wrappers.<AidComicEpisode>lambdaQuery()
                        .eq(AidComicEpisode::getId, id)
                        .eq(AidComicEpisode::getUserId, userId)
                        .eq(AidComicEpisode::getDelFlag, "0"));
        if (episode == null) {
            return null;
        }
        // 校验项目归属
        getAndCheckProject(episode.getProjectId(), userId);
        return episode;
    }

    @Override
    public AidComicEpisode insertUserEpisode(UserEpisodeCreateRequest request, Long userId)
    {
        // 校验项目归属
        AidComicProject project = getAndCheckProject(request.getProjectId(), userId);

        // 电影类型不允许添加剧集
        if (ProjectTypeEnum.MOVIE.getValue().equals(project.getProjectType())) {
            throw new RuntimeException("电影类型项目无法添加剧集");
        }

        // 校验枚举字段
        validateEnumFields(request.getGenMode(), request.getCreationMode());

        // 计算集数：当前最大集数+1
        Long maxEpisodeNo = aidComicEpisodeService.getObj(
                Wrappers.<AidComicEpisode>lambdaQuery()
                        .select(AidComicEpisode::getEpisodeNo)
                        .eq(AidComicEpisode::getProjectId, request.getProjectId())
                        .eq(AidComicEpisode::getDelFlag, "0")
                        .orderByDesc(AidComicEpisode::getEpisodeNo)
                        .last("LIMIT 1"),
                obj -> obj != null ? ((Number) obj).longValue() : 0L);
        if (maxEpisodeNo == null) {
            maxEpisodeNo = 0L;
        }

        AidComicEpisode episode = new AidComicEpisode();
        episode.setProjectId(request.getProjectId());
        episode.setUserId(userId);
        episode.setEpisodeNo(maxEpisodeNo + 1);
        episode.setComicTitle(request.getComicTitle());
        episode.setComicDesc(request.getComicDesc());
        episode.setComicCoverUrl(request.getComicCoverUrl());
        // 从项目继承参数，剧集表没有这些字段，VO里从项目带出
        // 生成模式、创作模式：优先用请求传入的，没传则继承项目默认值
        episode.setGenMode(StringUtils.isNotEmpty(request.getGenMode()) ? request.getGenMode() : project.getDefaultGenMode());
        episode.setCreationMode(StringUtils.isNotEmpty(request.getCreationMode()) ? request.getCreationMode() : project.getDefaultCreationMode());
        episode.setStatus(0);
        episode.setDelFlag("0");
        episode.setCreateBy(String.valueOf(userId));
        episode.setCreateTime(DateUtils.getNowDate());
        aidComicEpisodeService.save(episode);
        return episode;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AidComicEpisode updateUserEpisode(UserEpisodeUpdateRequest request, Long userId)
    {
        // 查询剧集并校验归属
        AidComicEpisode episode = selectUserEpisodeById(request.getId(), userId);
        if (episode == null) {
            throw new RuntimeException("剧集不存在或无权限操作");
        }
        AidComicProject project = getAndCheckProject(episode.getProjectId(), userId);

        // 校验枚举字段
        validateEnumFields(request.getGenMode(), request.getCreationMode());

        Integer beforeStatus = episode.getStatus();
        String candidateTitle = episode.getComicTitle();
        String candidateDesc = episode.getComicDesc();
        String candidateCoverUrl = episode.getComicCoverUrl();
        if (StringUtils.isNotEmpty(request.getComicTitle())) {
            candidateTitle = request.getComicTitle().trim();
        }
        if (request.getComicDesc() != null) {
            candidateDesc = request.getComicDesc().trim();
        }
        if (request.getComicCoverUrl() != null) {
            String requestCoverUrl = request.getComicCoverUrl().trim();
            candidateCoverUrl = StrUtil.isBlank(requestCoverUrl)
                    ? requestCoverUrl : mediaUrlResolver.toRelativePath(requestCoverUrl);
        }
        // 更新可修改字段（aspectRatio、scriptType、videoStyleType、videoStyleValue来自项目，不可修改）
        boolean needClearGenConfig = false; // 创作模式跨组切换时置 true，保存成功后清空项目级生成配置
        if (StringUtils.isNotEmpty(request.getComicTitle())) {
            episode.setComicTitle(candidateTitle);
        }
        if (request.getComicDesc() != null) {
            episode.setComicDesc(candidateDesc);
        }
        if (request.getComicCoverUrl() != null) {
            episode.setComicCoverUrl(candidateCoverUrl);
        }
        if (request.getGenMode() != null) {
            episode.setGenMode(request.getGenMode());
        }
        if (request.getCreationMode() != null) {
            // 创作模式锁定：该剧集分镜一旦已生成，禁止再改创作模式（仅在实际变更时拦截）
            String newMode = request.getCreationMode();
            String oldMode = episode.getCreationMode();
            boolean changingCreationMode = !java.util.Objects.equals(newMode, oldMode); // 是否实际变更创作模式
            if (changingCreationMode
                    && hasStoryboard(episode.getProjectId(), episode.getId())) {
                log.info("修改剧集拒绝：剧集分镜已生成，创作模式锁定: projectId={}, episodeId={}, old={}, new={}",
                        episode.getProjectId(), episode.getId(), oldMode, newMode);
                throw new ServiceException("分镜已生成，不可改创作模式");
            }
            // 跨组切换（标准 i2v/multi ↔ 进阶 pro/auto_grid）：旧组配置不再适用，标记保存后清空项目级生成配置
            needClearGenConfig = changingCreationMode && CreationModeEnum.isCrossGroupSwitch(oldMode, newMode);
            episode.setCreationMode(newMode);
        }
        episode.setUpdateBy(String.valueOf(userId));
        episode.setUpdateTime(DateUtils.getNowDate());
        LambdaUpdateWrapper<AidComicEpisode> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidComicEpisode::getId, episode.getId());
        updateWrapper.eq(AidComicEpisode::getUserId, userId);
        updateWrapper.eq(AidComicEpisode::getStatus, beforeStatus);
        boolean updated = aidComicEpisodeService.update(episode, updateWrapper);
        if (!updated) {
            log.info("修改剧集失败，状态已变化: episodeId={}, beforeStatus={}", episode.getId(), beforeStatus);
            throw new ServiceException("状态已变化");
        }
        // 创作模式跨组切换且保存成功：清空该项目生成配置（项目级，按当前用户），使各场景回落矩阵默认
        if (needClearGenConfig) {
            projectGenConfigService.clearProjectConfig(episode.getProjectId(), userId);
        }
        return episode;
    }

    /**
     * 判断指定项目+剧集下是否已存在有效分镜。
     * 用于创作模式锁定：分镜一旦生成，禁止再改该剧集创作模式。
     */
    private boolean hasStoryboard(Long projectId, Long episodeId) {
        if (projectId == null || episodeId == null) {
            return false;
        }
        return aidStoryboardService.count(
                Wrappers.<com.aid.aid.domain.AidStoryboard>lambdaQuery()
                        .eq(com.aid.aid.domain.AidStoryboard::getProjectId, projectId)
                        .eq(com.aid.aid.domain.AidStoryboard::getEpisodeId, episodeId)
                        .eq(com.aid.aid.domain.AidStoryboard::getDelFlag, "0")) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int softDeleteUserEpisodeById(Long id, Long userId)
    {
        // 查询剧集并校验归属
        AidComicEpisode episode = selectUserEpisodeById(id, userId);
        if (episode == null) {
            throw new RuntimeException("剧集不存在或无权限操作");
        }
        Long projectId = episode.getProjectId();
        // 收集本次需清理的 OSS 文件，最后统一登记（先完成所有 DB 删除，再删文件）
        java.util.List<String> filesToClean = new java.util.ArrayList<>();
        generationArtifactCleanupService.cleanupByEpisode(projectId, id);
        List<AidEpisodeEditor> editors = aidEpisodeEditorService.list(Wrappers.<AidEpisodeEditor>lambdaQuery()
                .select(AidEpisodeEditor::getId, AidEpisodeEditor::getFinalVideoUrl, AidEpisodeEditor::getCoverUrl)
                .eq(AidEpisodeEditor::getProjectId, projectId)
                .eq(AidEpisodeEditor::getEpisodeId, id));
        if (!editors.isEmpty()) {
            for (AidEpisodeEditor editor : editors) {
                filesToClean.add(editor.getFinalVideoUrl());
                filesToClean.add(editor.getCoverUrl());
            }
            aidEpisodeEditorService.remove(Wrappers.<AidEpisodeEditor>lambdaQuery()
                    .eq(AidEpisodeEditor::getProjectId, projectId)
                    .eq(AidEpisodeEditor::getEpisodeId, id));
        }
        aidStoryboardService.remove(Wrappers.<AidStoryboard>lambdaQuery()
                .eq(AidStoryboard::getProjectId, projectId)
                .eq(AidStoryboard::getEpisodeId, id));
        // 级联清理该集关联数据（对齐项目级联删除范围，防止孤儿行长期累积）：
        // 剧本全部版本、场次、角色音色绑定
        aidComicScriptService.remove(Wrappers.<AidComicScript>lambdaQuery()
                .eq(AidComicScript::getProjectId, projectId)
                .eq(AidComicScript::getEpisodeId, id));
        aidScenePlotService.remove(Wrappers.<AidScenePlot>lambdaQuery()
                .eq(AidScenePlot::getProjectId, projectId)
                .eq(AidScenePlot::getEpisodeId, id));
        aidRoleVoiceBindingService.remove(Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                .eq(AidRoleVoiceBinding::getProjectId, projectId)
                .eq(AidRoleVoiceBinding::getEpisodeId, id));
        filesToClean.add(episode.getComicCoverUrl());
        int affected = aidComicEpisodeService.getBaseMapper().delete(Wrappers.<AidComicEpisode>lambdaQuery()
                .eq(AidComicEpisode::getId, id)
                .eq(AidComicEpisode::getUserId, userId)) > 0 ? 1 : 0;
        mediaOssCleanupService.cleanupFiles(filesToClean);
        return affected;
    }

    @Override
    public List<UserEpisodeVO> convertToVOList(List<AidComicEpisode> episodes)
    {
        List<UserEpisodeVO> result = new ArrayList<>();
        if (episodes == null || episodes.isEmpty()) {
            return result;
        }
        // 收集项目ID与剧集ID（列表接口通常同一项目，兼容多项目场景）
        Set<Long> projectIds = new LinkedHashSet<>();
        Set<Long> episodeIds = new LinkedHashSet<>();
        for (AidComicEpisode episode : episodes) {
            projectIds.add(episode.getProjectId());
            episodeIds.add(episode.getId());
        }
        // 查询字段精简：VO 只需项目的画面比例/剧本类型/风格字段（新增 VO 字段时此处必须同步补充）
        Map<Long, AidComicProject> projectMap = new HashMap<>();
        for (AidComicProject project : aidComicProjectService.list(Wrappers.<AidComicProject>lambdaQuery()
                .select(AidComicProject::getId, AidComicProject::getAspectRatio, AidComicProject::getScriptType,
                        AidComicProject::getVideoStyleType, AidComicProject::getVideoStyleValue)
                .in(AidComicProject::getId, projectIds)
                .eq(AidComicProject::getDelFlag, "0"))) {
            projectMap.put(project.getId(), project);
        }
        // 查询字段精简：成片展示只需 id/归属/成片地址/待审片/导出状态（新增 VO 字段时此处必须同步补充）
        // 按 id 升序遍历后写覆盖，同一集多条剪辑记录时保留最新一条
        Long userId = episodes.get(0).getUserId();
        Map<String, AidEpisodeEditor> editorMap = new HashMap<>();
        for (AidEpisodeEditor editor : aidEpisodeEditorService.list(Wrappers.<AidEpisodeEditor>lambdaQuery()
                .select(AidEpisodeEditor::getId, AidEpisodeEditor::getProjectId, AidEpisodeEditor::getEpisodeId,
                        AidEpisodeEditor::getFinalVideoUrl,
                        AidEpisodeEditor::getExportStatus)
                .eq(Objects.nonNull(userId), AidEpisodeEditor::getUserId, userId)
                .in(AidEpisodeEditor::getProjectId, projectIds)
                .in(AidEpisodeEditor::getEpisodeId, episodeIds)
                .eq(AidEpisodeEditor::getDelFlag, "0")
                .orderByAsc(AidEpisodeEditor::getId))) {
            editorMap.put(editor.getProjectId() + "_" + editor.getEpisodeId(), editor);
        }
        for (AidComicEpisode episode : episodes) {
            result.add(buildEpisodeVO(episode,
                    projectMap.get(episode.getProjectId()),
                    editorMap.get(episode.getProjectId() + "_" + episode.getId())));
        }
        return result;
    }

    @Override
    public UserEpisodeVO convertToVO(AidComicEpisode episode)
    {
        if (episode == null) {
            return null;
        }
        List<UserEpisodeVO> list = convertToVOList(Collections.singletonList(episode));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 组装剧集 VO：剧集自身字段 + 所属项目配置字段 + 最新成片信息。
     *
     * @param episode 剧集实体
     * @param project 所属项目（可为 null）
     * @param editor  该集最新剪辑记录（可为 null，表示从未导出）
     * @return 剧集 VO
     */
    private UserEpisodeVO buildEpisodeVO(AidComicEpisode episode, AidComicProject project, AidEpisodeEditor editor)
    {
        UserEpisodeVO.UserEpisodeVOBuilder builder = UserEpisodeVO.builder()
                .id(episode.getId())
                .projectId(episode.getProjectId())
                .episodeNo(episode.getEpisodeNo())
                .comicTitle(episode.getComicTitle())
                .comicDesc(episode.getComicDesc())
                .comicCoverUrl(episode.getComicCoverUrl())
                .genMode(episode.getGenMode())
                .creationMode(episode.getCreationMode())
                .status(episode.getStatus())
                .statusReason(episode.getStatusReason())
                .createTime(episode.getCreateTime())
                .updateTime(episode.getUpdateTime());
        if (project != null) {
            builder.aspectRatio(project.getAspectRatio())
                    .scriptType(project.getScriptType())
                    .videoStyleType(project.getVideoStyleType())
                    .videoStyleValue(project.getVideoStyleValue());
        }
        if (editor != null) {
            builder.episodeEditorId(editor.getId())
                    .finalVideoUrl(editor.getFinalVideoUrl())
                    .exportStatus(editor.getExportStatus());
        }
        return builder.build();
    }
}
