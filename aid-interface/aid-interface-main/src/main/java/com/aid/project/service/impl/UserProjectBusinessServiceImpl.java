package com.aid.project.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.LinkedHashSet;
import java.util.Set;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidUserComicAsset;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidEpisodeEditorService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.aid.service.IAidUserComicAssetService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.common.page.SafePageUtils;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.StringUtils;
import com.aid.compose.ComposeConstants;
import com.aid.enums.AspectRatioEnum;
import com.aid.enums.CreationModeEnum;
import com.aid.enums.EpisodeStatusEnum;
import com.aid.enums.GenModeEnum;
import com.aid.enums.ProjectStatusEnum;
import com.aid.enums.ProjectTypeEnum;
import com.aid.enums.ScriptTypeEnum;
import com.aid.project.dto.UserProjectCreateRequest;
import com.aid.project.dto.UserProjectQueryRequest;
import com.aid.project.dto.UserProjectUpdateRequest;
import com.aid.project.service.IUserProjectBusinessService;
import com.aid.project.service.ProjectStyleSnapshotService;
import com.aid.project.service.ProjectStyleSnapshotService.ResolvedProjectStyle;
import com.aid.project.vo.UserProjectVO;
import com.aid.projectgenconfig.service.IProjectGenConfigService;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户项目业务Service实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class UserProjectBusinessServiceImpl implements IUserProjectBusinessService
{
    /** 删除标志：正常（未删除） */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 电影成片在 aid_episode_editor 中的剧集ID标识 */
    private static final Long MOVIE_EPISODE_ID = 0L;

    /** 创作模式进阶组标识（专业版 pro + 自动宫格 auto_grid）；进阶组不支持真人解说剧本 */
    private static final String CREATION_MODE_GROUP_ADVANCED = "advanced";

    /** 资产类型：风格（风格图禁止作为项目封面） */
    private static final String ASSET_TYPE_STYLE = "style";

    /** 资产提取父任务类型 */
    private static final String TASK_TYPE_ASSET_EXTRACT = "asset_extract";

    /** 会锁定项目风格的提取任务状态 */
    private static final List<String> ACTIVE_EXTRACT_STATUSES = List.of(
            "PENDING", "QUEUED", "PROCESSING", "FINALIZING", "RECOVERING");

    @Autowired
    private IAidComicProjectService aidComicProjectService;

    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;

    @Autowired
    private IAidEpisodeEditorService aidEpisodeEditorService;

    @Autowired
    private IProjectGenConfigService projectGenConfigService;

    @Autowired
    private IAidStoryboardService aidStoryboardService;

    /** 项目级联删除服务：硬删项目及其全部子数据并清 OSS */
    @Autowired
    private com.aid.project.service.IProjectCascadeDeleteService projectCascadeDeleteService;

    /** 用户风格图查询：项目封面禁止复用风格图（防止删项目/风格图相互牵连） */
    @Autowired
    private IAidUserComicAssetService aidUserComicAssetService;

    @Autowired
    private IAidRolePropSceneService rolePropSceneService;

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private ProjectStyleSnapshotService projectStyleSnapshotService;

    /** 媒体URL统一解析器：封面URL归一化为相对路径/全URL，避免存储格式不一致漏匹配 */
    @Autowired
    private MediaUrlResolver mediaUrlResolver;

    /**
     * 查询用户的项目列表（带软删除过滤）
     *
     * @param request 查询条件
     * @param userId 用户ID
     * @return 项目列表
     */
    @Override
    public List<AidComicProject> selectUserProjectList(UserProjectQueryRequest request, Long userId)
    {
        LambdaQueryWrapper<AidComicProject> wrapper = Wrappers.lambdaQuery();
        // 查询字段精简：C端列表禁止读取隐藏风格快照；新增展示字段时必须同步补充。
        wrapper.select(AidComicProject::getId, AidComicProject::getUserId,
                AidComicProject::getProjectName, AidComicProject::getProjectDesc,
                AidComicProject::getProjectType, AidComicProject::getCoverUrl,
                AidComicProject::getAspectRatio,
                AidComicProject::getScriptType, AidComicProject::getVideoStyleType,
                AidComicProject::getVideoStyleValue, AidComicProject::getStyleSource,
                AidComicProject::getStyleAssetId, AidComicProject::getDefaultGenMode,
                AidComicProject::getDefaultCreationMode, AidComicProject::getCurrentStep,
                AidComicProject::getStatus, AidComicProject::getStatusReason,
                AidComicProject::getCreateTime, AidComicProject::getUpdateTime);
        // 只查询当前用户的项目
        wrapper.eq(AidComicProject::getUserId, userId);
        // 过滤已删除的记录
        wrapper.eq(AidComicProject::getDelFlag, "0");
        // 项目名称模糊查询
        if (StringUtils.isNotEmpty(request.getProjectName())) {
            wrapper.like(AidComicProject::getProjectName, request.getProjectName());
        }
        // 项目类型筛选
        if (StringUtils.isNotEmpty(request.getProjectType())) {
            wrapper.eq(AidComicProject::getProjectType, request.getProjectType());
        }
        // 状态筛选
        if (request.getStatus() != null) {
            wrapper.eq(AidComicProject::getStatus, request.getStatus());
        }
        // 按创建时间倒序
        wrapper.orderByDesc(AidComicProject::getCreateTime);
        // 分页紧邻列表查询开启（钳制 pageSize 上限，防前端乱传拉爆内存）
        SafePageUtils.startClampedPage();
        return aidComicProjectService.list(wrapper);
    }

    /**
     * 查询用户的项目详情（带归属校验）
     *
     * @param id 项目ID
     * @param userId 用户ID
     * @return 项目详情
     */
    @Override
    public AidComicProject selectUserProjectById(Long id, Long userId)
    {
        LambdaQueryWrapper<AidComicProject> wrapper = Wrappers.lambdaQuery();
        // 查询字段精简：C端详情不读取隐藏风格快照，避免内部模板进入序列化上下文。
        wrapper.select(AidComicProject::getId, AidComicProject::getUserId,
                AidComicProject::getProjectName, AidComicProject::getProjectDesc,
                AidComicProject::getProjectType, AidComicProject::getCoverUrl,
                AidComicProject::getAspectRatio,
                AidComicProject::getScriptType, AidComicProject::getVideoStyleType,
                AidComicProject::getVideoStyleValue, AidComicProject::getStyleSource,
                AidComicProject::getStyleAssetId, AidComicProject::getDefaultGenMode,
                AidComicProject::getDefaultCreationMode, AidComicProject::getCurrentStep,
                AidComicProject::getStatus, AidComicProject::getStatusReason,
                AidComicProject::getCreateTime, AidComicProject::getUpdateTime);
        wrapper.eq(AidComicProject::getId, id);
        wrapper.eq(AidComicProject::getUserId, userId);
        wrapper.eq(AidComicProject::getDelFlag, "0");
        return aidComicProjectService.getOne(wrapper);
    }

    /**
     * 更新事务内锁定项目主行。资产创建/提取任务建档使用同一行锁，消除风格切换竞态。
     */
    private AidComicProject selectUserProjectByIdForUpdate(Long id, Long userId)
    {
        return aidComicProjectService.getOne(Wrappers.<AidComicProject>lambdaQuery()
                .eq(AidComicProject::getId, id)
                .eq(AidComicProject::getUserId, userId)
                .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL)
                .last("FOR UPDATE"));
    }

    /**
     * 校验枚举字段值是否合法。
     *
     * @param projectType 项目类型
     * @param aspectRatio 画面比例
     * @param scriptType 剧本类型
     * @param defaultGenMode 默认生成模式
     * @param defaultCreationMode 默认创作模式
     */
    private void validateEnumFields(String projectType, String aspectRatio, String scriptType,
                                     String defaultGenMode, String defaultCreationMode)
    {
        if (StringUtils.isNotEmpty(projectType) && ProjectTypeEnum.getByValue(projectType) == null) {
            throw new RuntimeException("项目类型参数错误");
        }
        if (StringUtils.isNotEmpty(aspectRatio) && AspectRatioEnum.getByValue(aspectRatio) == null) {
            throw new RuntimeException("画面比例参数错误");
        }
        if (StringUtils.isNotEmpty(scriptType) && ScriptTypeEnum.getByValue(scriptType) == null) {
            throw new RuntimeException("剧本类型参数错误");
        }
        // videoStyleType 为风格名称，不做枚举校验，前端传什么存什么
        if (StringUtils.isNotEmpty(defaultGenMode) && GenModeEnum.getByValue(defaultGenMode) == null) {
            throw new RuntimeException("生成模式参数错误");
        }
        if (StringUtils.isNotEmpty(defaultCreationMode) && CreationModeEnum.getByValue(defaultCreationMode) == null) {
            throw new RuntimeException("创作模式参数错误");
        }
    }

    /**
     * 校验「剧本类型 × 创作模式」兼容性：专业版(pro) / 自动宫格(auto_grid) 进阶组不支持真人解说(monologue)。
     * 原因：进阶组（pro/auto_grid）的视频提示词矩阵（{@code aid_gen_agent_pool}）当前仅配置了
     * 剧情演绎(plot)，真人解说无对应智能体，若放行后续生成会直接报"智能体未配置"，故在项目配置阶段前置拦截。
     *
     * @param scriptType   剧本类型（plot 剧情演绎 / monologue 真人解说）
     * @param creationMode 创作模式（i2v / multi / pro / auto_grid）
     */
    private void validateScriptTypeCreationModeCompat(String scriptType, String creationMode)
    {
        // 剧本类型或创作模式任一为空 → 不在此处拦截，交由各自的必填 / 枚举校验处理
        if (StrUtil.isBlank(scriptType) || StrUtil.isBlank(creationMode)) {
            return;
        }
        CreationModeEnum mode = CreationModeEnum.getByValue(creationMode); // 解析创作模式枚举
        // 进阶组（专业版 / 宫格）+ 真人解说 → 拒绝（解说无对应智能体配置）
        if (Objects.nonNull(mode)
                && CREATION_MODE_GROUP_ADVANCED.equals(mode.getGroup())
                && ScriptTypeEnum.MONOLOGUE.getValue().equals(scriptType)) {
            log.info("项目配置拒绝：进阶组创作模式不支持解说, scriptType={}, creationMode={}", scriptType, creationMode);
            throw new ServiceException("该模式不支持解说");
        }
    }

    /**
     * 校验项目封面不得复用「用户风格图」。
     * 风格图属于个人参考资产库(aid_user_comic_asset, asset_type=style)，若被当作项目封面复用同一 OSS
     * 文件，会造成删项目与删风格图相互牵连，故在设置封面时前置拒绝。
     *
     * @param coverUrl 封面URL（相对路径或全URL），为空则跳过
     * @param userId   当前用户ID
     */
    private void assertCoverNotStyleAsset(String coverUrl, Long userId)
    {
        if (StrUtil.isBlank(coverUrl)) {
            return;
        }
        // 归一化候选值：原值 + 相对路径 + 全URL，避免封面与风格图存储格式不一致漏匹配
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(coverUrl.trim());
        String relative = mediaUrlResolver.toRelativePath(coverUrl);
        if (StrUtil.isNotBlank(relative)) {
            candidates.add(relative.trim());
        }
        String full = mediaUrlResolver.toFullUrl(coverUrl);
        if (StrUtil.isNotBlank(full)) {
            candidates.add(full.trim());
        }
        // 命中当前用户任意一张风格图 → 拒绝
        LambdaQueryWrapper<AidUserComicAsset> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidUserComicAsset::getUserId, userId);
        wrapper.eq(AidUserComicAsset::getAssetType, ASSET_TYPE_STYLE);
        wrapper.eq(AidUserComicAsset::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.in(AidUserComicAsset::getImageUrl, candidates);
        if (aidUserComicAssetService.count(wrapper) > 0) {
            log.info("项目封面拒绝：不允许使用风格图作为封面, userId={}, coverUrl={}", userId, coverUrl);
            throw new ServiceException("封面不能用风格图");
        }
    }

    /**
     * 用户创建项目
     *
     * @param request 创建请求
     * @param userId 用户ID
     * @return 新增的项目
     */
    @Override
    public AidComicProject insertUserProject(UserProjectCreateRequest request, Long userId)
    {
        // 校验枚举字段
        validateEnumFields(request.getProjectType(), request.getAspectRatio(), request.getScriptType(),
                request.getDefaultGenMode(), request.getDefaultCreationMode());

        // 剧本类型 × 创作模式兼容性：专业版/宫格不支持真人解说，直接拒绝
        validateScriptTypeCreationModeCompat(request.getScriptType(), request.getDefaultCreationMode());

        // 封面不得复用风格图
        assertCoverNotStyleAsset(request.getCoverUrl(), userId);

        // 电影类型必填校验
        boolean isMovie = ProjectTypeEnum.MOVIE.getValue().equals(request.getProjectType());
        if (isMovie) {
            if (StringUtils.isEmpty(request.getAspectRatio())) {
                throw new ServiceException("请先设置画面比例");
            }
            if (StringUtils.isEmpty(request.getScriptType())) {
                throw new ServiceException("请先设置剧本类型");
            }
        }

        ResolvedProjectStyle resolvedStyle = null;
        boolean hasStyleSelector = StrUtil.isNotBlank(request.getStyleSource())
                || Objects.nonNull(request.getStyleAssetId());
        if (hasStyleSelector) {
            // 新协议：只信任来源和资产ID，公开描述及隐藏模板均由后端读取。
            resolvedStyle = projectStyleSnapshotService.resolve(
                    request.getStyleSource(), request.getStyleAssetId(), userId);
        }
        String styleName = Objects.nonNull(resolvedStyle)
                ? resolvedStyle.styleName() : request.getVideoStyleType();
        String stylePrompt = Objects.nonNull(resolvedStyle)
                ? resolvedStyle.publicPrompt() : request.getVideoStyleValue();

        // 兼容旧客户端：未传来源和ID时仍沿用公开风格字段，隐藏角色模板运行时回退公开描述。
        if (StrUtil.isBlank(styleName) || StrUtil.isBlank(stylePrompt)) {
            log.info("创建项目失败，未设置视频风格: userId={}, styleType={}, styleValue={}",
                    userId, styleName, stylePrompt);
            throw new ServiceException("请选择风格");
        }

        AidComicProject project = new AidComicProject();
        project.setUserId(userId);
        project.setProjectName(request.getProjectName());
        project.setProjectDesc(request.getProjectDesc());
        project.setProjectType(request.getProjectType());
        project.setCoverUrl(request.getCoverUrl());
        project.setAspectRatio(request.getAspectRatio());
        project.setScriptType(request.getScriptType());
        project.setVideoStyleType(styleName);
        project.setVideoStyleValue(stylePrompt);
        project.setStyleSource(Objects.nonNull(resolvedStyle)
                ? request.getStyleSource().trim().toLowerCase(Locale.ROOT) : null);
        project.setStyleAssetId(Objects.nonNull(resolvedStyle) ? request.getStyleAssetId() : null);
        project.setHiddenStylePromptJson(Objects.nonNull(resolvedStyle)
                ? resolvedStyle.hiddenPromptJson() : null);
        project.setDefaultGenMode(request.getDefaultGenMode());
        project.setDefaultCreationMode(request.getDefaultCreationMode());
        // 初始化当前步骤：电影从1开始，剧集固定-1（步骤由episode表管理）
        project.setCurrentStep(isMovie ? 1 : -1);
        // 默认状态为草稿
        project.setStatus(0);
        // 未删除
        project.setDelFlag("0");
        project.setCreateTime(DateUtils.getNowDate());
        project.setCreateBy(String.valueOf(userId));
        aidComicProjectService.save(project);
        return project;
    }

    /**
     * 用户修改项目（带归属校验）
     *
     * @param request 修改请求
     * @param userId 用户ID
     * @return 修改后的项目
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AidComicProject updateUserProject(UserProjectUpdateRequest request, Long userId)
    {
        // 与资产创建/提取任务建档共用项目行锁，直至本事务提交，消除风格切换 TOCTOU。
        AidComicProject project = this.selectUserProjectByIdForUpdate(request.getId(), userId);
        if (project == null) {
            throw new RuntimeException("项目不存在或无权限操作");
        }
        boolean styleSelectorTouched = request.getStyleSource() != null || request.getStyleAssetId() != null;
        boolean legacyStyleTouched = request.getVideoStyleType() != null || request.getVideoStyleValue() != null;
        ResolvedProjectStyle resolvedStyle = null;
        String candidateStyleName = null;
        String candidateStylePrompt = null;
        String candidateStyleSource = null;
        Long candidateStyleAssetId = null;
        if (styleSelectorTouched) {
            if (StrUtil.isBlank(request.getStyleSource()) || Objects.isNull(request.getStyleAssetId())) {
                log.info("修改项目拒绝：风格来源与ID必须同时传: projectId={}, source={}, assetId={}",
                        project.getId(), request.getStyleSource(), request.getStyleAssetId());
                throw new ServiceException("请选择风格");
            }
            resolvedStyle = projectStyleSnapshotService.resolve(
                    request.getStyleSource(), request.getStyleAssetId(), userId);
            candidateStyleName = resolvedStyle.styleName();
            candidateStylePrompt = resolvedStyle.publicPrompt();
            candidateStyleSource = request.getStyleSource().trim().toLowerCase(Locale.ROOT);
            candidateStyleAssetId = request.getStyleAssetId();
        } else if (legacyStyleTouched) {
            candidateStyleName = request.getVideoStyleType();
            candidateStylePrompt = request.getVideoStyleValue();
            if (StrUtil.isBlank(candidateStyleName) || StrUtil.isBlank(candidateStylePrompt)) {
                log.info("修改项目拒绝：风格名称与描述必须同时传: projectId={}", project.getId());
                throw new ServiceException("请选择风格");
            }
        }
        boolean changingStyle = (styleSelectorTouched || legacyStyleTouched)
                && (!Objects.equals(candidateStyleName, project.getVideoStyleType())
                || !Objects.equals(candidateStylePrompt, project.getVideoStyleValue())
                || (styleSelectorTouched
                && (!Objects.equals(candidateStyleSource, project.getStyleSource())
                || !Objects.equals(candidateStyleAssetId, project.getStyleAssetId())
                || !Objects.equals(resolvedStyle.hiddenPromptJson(), project.getHiddenStylePromptJson()))));

        // 剧集记录只是导入剧本后的内容容器，不能作为项目配置锁；真实下游数据分别由资产锁和分镜锁保护。
        validateEnumFields(null, request.getAspectRatio(), request.getScriptType(),
                request.getDefaultGenMode(), request.getDefaultCreationMode());
        // 分镜已生成锁：项目下任意剧集/电影分镜已存在时，禁止切换 经济/性能(defaultGenMode)
        // 与 解说/演绎(scriptType)；分镜未生成时全量放行（与「创作模式锁定」同口径）
        boolean changingGenMode = request.getDefaultGenMode() != null
                && !Objects.equals(request.getDefaultGenMode(), project.getDefaultGenMode()); // 是否切换经济/性能
        boolean changingScriptType = request.getScriptType() != null
                && !Objects.equals(request.getScriptType(), project.getScriptType()); // 是否修改解说/演绎
        if ((changingGenMode || changingScriptType) && hasAnyStoryboardInProject(project.getId())) {
            log.info("项目已生成分镜，拒绝修改生成模式/剧本类型: projectId={}, changingGenMode={}, changingScriptType={}",
                    project.getId(), changingGenMode, changingScriptType);
            throw new ServiceException("分镜已生成，无法修改");
        }
        Integer beforeStatus = project.getStatus();
        String candidateProjectName = project.getProjectName();
        String candidateProjectDesc = project.getProjectDesc();
        String candidateCoverUrl = project.getCoverUrl();
        if (StringUtils.isNotEmpty(request.getProjectName())) {
            candidateProjectName = request.getProjectName().trim();
        }
        if (request.getProjectDesc() != null) {
            candidateProjectDesc = request.getProjectDesc().trim();
        }
        if (request.getCoverUrl() != null) {
            // 封面不得复用风格图，并统一保存对象存储相对路径。
            assertCoverNotStyleAsset(request.getCoverUrl(), userId);
            candidateCoverUrl = StrUtil.isBlank(request.getCoverUrl())
                    ? request.getCoverUrl() : mediaUrlResolver.toRelativePath(request.getCoverUrl());
        }
        // 更新字段
        boolean needClearGenConfig = false; // 创作模式跨组切换时置 true，保存成功后清空项目级生成配置
        if (StringUtils.isNotEmpty(request.getProjectName())) {
            project.setProjectName(candidateProjectName);
        }
        if (request.getProjectDesc() != null) {
            project.setProjectDesc(candidateProjectDesc);
        }
        if (request.getCoverUrl() != null) {
            project.setCoverUrl(candidateCoverUrl);
        }
        if (request.getAspectRatio() != null) {
            project.setAspectRatio(request.getAspectRatio());
        }
        if (request.getScriptType() != null) {
            project.setScriptType(request.getScriptType());
        }
        if (changingStyle) {
            assertStyleSwitchAllowed(project.getId());
            project.setVideoStyleType(candidateStyleName);
            project.setVideoStyleValue(candidateStylePrompt);
            project.setStyleSource(candidateStyleSource);
            project.setStyleAssetId(candidateStyleAssetId);
            // 新协议复制源模板；旧协议切换清空旧快照，角色链路安全回退新的公开风格。
            project.setHiddenStylePromptJson(Objects.nonNull(resolvedStyle)
                    ? resolvedStyle.hiddenPromptJson() : null);
        }
        if (request.getDefaultGenMode() != null) {
            project.setDefaultGenMode(request.getDefaultGenMode());
        }
        if (request.getDefaultCreationMode() != null) {
            // 创作模式锁定：项目下任意分镜已生成时，禁止再改创作模式（与 经济/性能、解说/演绎 同口径）
            String newMode = request.getDefaultCreationMode();
            String oldMode = project.getDefaultCreationMode();
            boolean changingCreationMode = !java.util.Objects.equals(newMode, oldMode); // 是否实际变更创作模式
            if (changingCreationMode
                    && hasAnyStoryboardInProject(project.getId())) {
                log.info("修改项目拒绝：分镜已生成，创作模式锁定: projectId={}, old={}, new={}",
                        project.getId(), oldMode, newMode);
                throw new ServiceException("分镜已生成，不可改创作模式");
            }
            // 跨组切换（标准 i2v/multi ↔ 进阶 pro/auto_grid）：旧组配置不再适用，标记保存后清空项目级生成配置
            needClearGenConfig = changingCreationMode && CreationModeEnum.isCrossGroupSwitch(oldMode, newMode);
            project.setDefaultCreationMode(newMode);
        }
        if (!changingStyle) {
            // 普通保存不重复写入隐藏长快照；MyBatis-Plus 会忽略实体中的 null。
            project.setHiddenStylePromptJson(null);
        }
        project.setUpdateTime(DateUtils.getNowDate());
        project.setUpdateBy(String.valueOf(userId));
        // 剧本类型 × 创作模式兼容性（用合并后的最终值校验）：专业版/宫格不支持真人解说，直接拒绝
        validateScriptTypeCreationModeCompat(project.getScriptType(), project.getDefaultCreationMode());
        LambdaUpdateWrapper<AidComicProject> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidComicProject::getId, project.getId());
        updateWrapper.eq(AidComicProject::getUserId, userId);
        updateWrapper.eq(AidComicProject::getStatus, beforeStatus);
        if (changingStyle && (Objects.isNull(resolvedStyle)
                || Objects.isNull(resolvedStyle.hiddenPromptJson()))) {
            // MyBatis-Plus 默认忽略 null，需显式清除旧项目隐藏快照，防止串用上一风格。
            updateWrapper.set(AidComicProject::getHiddenStylePromptJson, null);
        }
        if (changingStyle && Objects.isNull(resolvedStyle)) {
            updateWrapper.set(AidComicProject::getStyleSource, null);
            updateWrapper.set(AidComicProject::getStyleAssetId, null);
        }
        boolean updated = aidComicProjectService.update(project, updateWrapper);
        if (!updated) {
            log.info("修改项目失败，状态已变化: projectId={}, beforeStatus={}", project.getId(), beforeStatus);
            throw new ServiceException("状态已变化");
        }
        // 创作模式跨组切换且保存成功：清空该项目生成配置，使各场景回落矩阵默认（组内切换不清空）
        if (needClearGenConfig) {
            projectGenConfigService.clearProjectConfig(project.getId(), userId);
        }
        return project;
    }

    /**
     * 判断项目下是否已存在任意有效分镜（不限剧集，电影主线 + 各分集统一口径）。
     * 用于"修改项目"接口的锁定判定：分镜一旦生成（无论电影 episode_id=0 还是任意剧集分集），
     * 就禁止再改 默认创作模式 / 经济性能 / 解说演绎，避免与已生成分镜的智能体/结构口径不一致。
     */
    private boolean hasAnyStoryboardInProject(Long projectId) {
        if (projectId == null) {
            return false;
        }
        return aidStoryboardService.count(
                Wrappers.<com.aid.aid.domain.AidStoryboard>lambdaQuery()
                        .eq(com.aid.aid.domain.AidStoryboard::getProjectId, projectId)
                        .eq(com.aid.aid.domain.AidStoryboard::getDelFlag, "0")) > 0;
    }

    /**
     * 项目行锁持有期间校验是否允许真正切换风格。
     */
    private void assertStyleSwitchAllowed(Long projectId)
    {
        // 查询字段精简：仅判断是否存在任一有效角色/场景/道具主资产。
        boolean hasAsset = rolePropSceneService.getOne(Wrappers.<AidRolePropScene>lambdaQuery()
                .select(AidRolePropScene::getId)
                .eq(AidRolePropScene::getProjectId, projectId)
                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"), false) != null;
        if (hasAsset) {
            log.info("修改项目拒绝：已有资产，风格锁定: projectId={}", projectId);
            throw new ServiceException("已有资产不可改");
        }
        // 查询字段精简：仅判断是否存在会与风格切换冲突的资产提取任务。
        boolean hasActiveTask = extractTaskService.getOne(Wrappers.<AidExtractTask>lambdaQuery()
                .select(AidExtractTask::getId)
                .eq(AidExtractTask::getProjectId, projectId)
                .eq(AidExtractTask::getTaskType, TASK_TYPE_ASSET_EXTRACT)
                .in(AidExtractTask::getStatus, ACTIVE_EXTRACT_STATUSES)
                .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"), false) != null;
        if (hasActiveTask) {
            log.info("修改项目拒绝：资产提取中，风格锁定: projectId={}", projectId);
            throw new ServiceException("资产提取中");
        }
    }

    /**
     * 用户删除项目（硬删除，带归属校验）。
     *
     * @param id 项目ID
     * @param userId 用户ID
     * @return 影响行数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int softDeleteUserProjectById(Long id, Long userId)
    {
        // 先查询并校验归属
        AidComicProject project = this.selectUserProjectById(id, userId);
        if (project == null) {
            throw new RuntimeException("项目不存在或无权限操作");
        }
        // 级联硬删除项目及其全部子数据 + 清理 OSS（先删文件、再删库）
        projectCascadeDeleteService.deleteProjectCascade(id, userId);
        return 1;
    }

    @Override
    public List<UserProjectVO> convertToVOList(List<AidComicProject> projects)
    {
        List<UserProjectVO> result = new ArrayList<>();
        if (projects == null || projects.isEmpty()) {
            return result;
        }
        // 仅电影模式项目有项目级成片（episode_id=0），批量查其最新剪辑记录；
        // 剧集类型项目批量统计集数，避免前端逐项目再调剧集列表接口
        Set<Long> movieProjectIds = new LinkedHashSet<>();
        Set<Long> seriesProjectIds = new LinkedHashSet<>();
        for (AidComicProject project : projects) {
            if (Objects.equals(ProjectTypeEnum.MOVIE.getValue(), project.getProjectType())) {
                movieProjectIds.add(project.getId());
            } else if (Objects.equals(ProjectTypeEnum.SERIES.getValue(), project.getProjectType())) {
                seriesProjectIds.add(project.getId());
            }
        }
        Long userId = projects.get(0).getUserId();
        Map<Long, AidEpisodeEditor> editorMap = new HashMap<>();
        if (!movieProjectIds.isEmpty()) {
            // 查询字段精简：成片展示只需 id/项目/成片地址/导出状态（新增 VO 字段时此处必须同步补充）
            // 按 id 升序遍历后写覆盖，同一项目多条剪辑记录时保留最新一条
            for (AidEpisodeEditor editor : aidEpisodeEditorService.list(Wrappers.<AidEpisodeEditor>lambdaQuery()
                    .select(AidEpisodeEditor::getId, AidEpisodeEditor::getProjectId,
                            AidEpisodeEditor::getFinalVideoUrl,
                            AidEpisodeEditor::getExportStatus)
                    .eq(Objects.nonNull(userId), AidEpisodeEditor::getUserId, userId)
                    .in(AidEpisodeEditor::getProjectId, movieProjectIds)
                    .eq(AidEpisodeEditor::getEpisodeId, MOVIE_EPISODE_ID)
                    .eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL)
                    .orderByAsc(AidEpisodeEditor::getId))) {
                editorMap.put(editor.getProjectId(), editor);
            }
        }
        // 剧集类型项目批量统计有效集数（一条 GROUP BY 查询），无集的项目在 Map 中缺失，组装时补 0
        Map<Long, Long> episodeCountMap = countEpisodesByProjectIds(seriesProjectIds, userId);
        Set<Long> allProjectIds = new LinkedHashSet<>();
        for (AidComicProject project : projects) {
            allProjectIds.add(project.getId());
        }
        Set<Long> styleLockedProjectIds = findStyleLockedProjectIds(allProjectIds);
        for (AidComicProject project : projects) {
            result.add(buildProjectVO(project, editorMap.get(project.getId()),
                    resolveEpisodeCount(project, episodeCountMap), styleLockedProjectIds.contains(project.getId())));
        }
        return result;
    }

    /**
     * 按项目ID批量统计未删除的剧集数量。
     *
     * @param seriesProjectIds 剧集类型项目ID集合
     * @param userId           项目归属用户ID（与成片查询同口径的防御过滤，可为 null）
     * @return projectId → 集数（仅包含有分集的项目）
     */
    private Map<Long, Long> countEpisodesByProjectIds(Set<Long> seriesProjectIds, Long userId)
    {
        Map<Long, Long> episodeCountMap = new HashMap<>();
        if (CollectionUtil.isEmpty(seriesProjectIds)) {
            return episodeCountMap;
        }
        // 查询字段精简：仅需 project_id 与聚合集数（走 idx_project_id 索引）
        QueryWrapper<AidComicEpisode> wrapper = new QueryWrapper<>();
        wrapper.select("project_id AS projectId", "COUNT(*) AS episodeTotal")
                .in("project_id", seriesProjectIds)
                .eq(Objects.nonNull(userId), "user_id", userId)
                .eq("del_flag", DEL_FLAG_NORMAL)
                .groupBy("project_id");
        for (Map<String, Object> row : aidComicEpisodeService.listMaps(wrapper)) {
            Object projectId = row.get("projectId");
            Object episodeTotal = row.get("episodeTotal");
            if (Objects.isNull(projectId) || Objects.isNull(episodeTotal)) {
                continue;
            }
            episodeCountMap.put(((Number) projectId).longValue(), ((Number) episodeTotal).longValue());
        }
        return episodeCountMap;
    }

    /**
     * 解析项目的集数出参：剧集类型返回实际集数（无集为 0），电影类型返回 null。
     *
     * @param project         项目实体
     * @param episodeCountMap projectId → 集数
     * @return 集数
     */
    private Long resolveEpisodeCount(AidComicProject project, Map<Long, Long> episodeCountMap)
    {
        if (!Objects.equals(ProjectTypeEnum.SERIES.getValue(), project.getProjectType())) {
            return null;
        }
        return episodeCountMap.getOrDefault(project.getId(), 0L);
    }

    /**
     * 批量计算风格锁，固定两条 GROUP BY 查询，避免项目列表逐行 count。
     */
    private Set<Long> findStyleLockedProjectIds(Set<Long> projectIds)
    {
        Set<Long> lockedIds = new LinkedHashSet<>();
        if (CollectionUtil.isEmpty(projectIds)) {
            return lockedIds;
        }
        LambdaQueryWrapper<AidRolePropScene> assetWrapper = Wrappers.lambdaQuery();
        assetWrapper.select(AidRolePropScene::getProjectId)
                .in(AidRolePropScene::getProjectId, projectIds)
                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                .groupBy(AidRolePropScene::getProjectId);
        for (AidRolePropScene asset : rolePropSceneService.list(assetWrapper)) {
            lockedIds.add(asset.getProjectId());
        }
        LambdaQueryWrapper<AidExtractTask> taskWrapper = Wrappers.lambdaQuery();
        taskWrapper.select(AidExtractTask::getProjectId)
                .in(AidExtractTask::getProjectId, projectIds)
                .eq(AidExtractTask::getTaskType, TASK_TYPE_ASSET_EXTRACT)
                .in(AidExtractTask::getStatus, ACTIVE_EXTRACT_STATUSES)
                .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                .groupBy(AidExtractTask::getProjectId);
        for (AidExtractTask task : extractTaskService.list(taskWrapper)) {
            lockedIds.add(task.getProjectId());
        }
        return lockedIds;
    }

    @Override
    public UserProjectVO convertToVO(AidComicProject project)
    {
        if (project == null) {
            return null;
        }
        List<UserProjectVO> list = convertToVOList(Collections.singletonList(project));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 组装项目 VO：项目自身字段 + 电影模式的项目级成片信息 + 剧集模式的集数。
     *
     * @param project      项目实体
     * @param editor       项目级剪辑记录（仅电影模式可能非 null）
     * @param episodeCount 剧集总集数（仅剧集类型非 null，无集为 0）
     * @param styleLocked  是否已锁定风格切换
     * @return 项目 VO
     */
    private UserProjectVO buildProjectVO(AidComicProject project, AidEpisodeEditor editor,
                                         Long episodeCount, boolean styleLocked)
    {
        UserProjectVO.UserProjectVOBuilder builder = UserProjectVO.builder()
                .id(project.getId())
                .projectName(project.getProjectName())
                .projectDesc(project.getProjectDesc())
                .projectType(project.getProjectType())
                .coverUrl(project.getCoverUrl())
                .aspectRatio(project.getAspectRatio())
                .scriptType(project.getScriptType())
                .videoStyleType(project.getVideoStyleType())
                .videoStyleValue(project.getVideoStyleValue())
                .styleSource(project.getStyleSource())
                .styleAssetId(project.getStyleAssetId())
                .styleLocked(styleLocked)
                .defaultGenMode(project.getDefaultGenMode())
                .defaultCreationMode(project.getDefaultCreationMode())
                .currentStep(project.getCurrentStep())
                .status(project.getStatus())
                .statusReason(project.getStatusReason())
                .episodeCount(episodeCount)
                .createTime(project.getCreateTime())
                .updateTime(project.getUpdateTime());
        if (editor != null) {
            builder.episodeEditorId(editor.getId())
                    .finalVideoUrl(editor.getFinalVideoUrl())
                    .exportStatus(editor.getExportStatus());
        }
        return builder.build();
    }
}
