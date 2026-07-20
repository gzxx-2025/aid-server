package com.aid.project.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
import com.aid.aid.domain.AidUserComicAsset;
import com.aid.aid.service.IAidComicAuditRecordService;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidEpisodeEditorService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.aid.service.IAidUserComicAssetService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.common.page.SafePageUtils;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.StringUtils;
import com.aid.enums.AspectRatioEnum;
import com.aid.enums.AuditActionEnum;
import com.aid.enums.AuditTargetTypeEnum;
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

    /** 是否公开：是 */
    private static final String IS_PUBLIC_YES = "1";

    /** 是否公开：否 */
    private static final String IS_PUBLIC_NO = "0";

    /** 电影成片在 aid_episode_editor 中的剧集ID标识 */
    private static final Long MOVIE_EPISODE_ID = 0L;

    /** 创作模式进阶组标识（专业版 pro + 自动宫格 auto_grid）；进阶组不支持真人解说剧本 */
    private static final String CREATION_MODE_GROUP_ADVANCED = "advanced";

    /** 资产类型：风格（风格图禁止作为项目封面） */
    private static final String ASSET_TYPE_STYLE = "style";

    @Autowired
    private IAidComicProjectService aidComicProjectService;

    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;

    @Autowired
    private IAidEpisodeEditorService aidEpisodeEditorService;

    @Autowired
    private IAidComicAuditRecordService aidComicAuditRecordService;

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

    /** 媒体URL统一解析器：封面URL归一化为相对路径/全URL，避免存储格式不一致漏匹配 */
    @Autowired
    private MediaUrlResolver mediaUrlResolver;

    /** 项目内容修改守卫：公开期间禁止修改内容，须先关闭公开 */
    @Autowired
    private com.aid.project.service.IProjectContentGuardService projectContentGuardService;

    /** 微信公众号推送：提交审核/发布状态变更通知（内部吞异常，不影响主流程） */
    @Autowired
    private com.aid.notify.wechat.service.IWechatNotifyService wechatNotifyService;

    /** 发布权限校验：总开关/用户级权限/白名单 */
    @Autowired
    private com.aid.publish.service.IPublishPermissionService publishPermissionService;

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
        wrapper.eq(AidComicProject::getId, id);
        wrapper.eq(AidComicProject::getUserId, userId);
        wrapper.eq(AidComicProject::getDelFlag, "0");
        return aidComicProjectService.getOne(wrapper);
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

        // 视频风格必填校验：项目必须选择风格，后续形态图/镜头/视频生成全链需要画风一致
        if (StrUtil.isBlank(request.getVideoStyleType()) || StrUtil.isBlank(request.getVideoStyleValue())) {
            log.info("创建项目失败，未设置视频风格: userId={}, styleType={}, styleValue={}",
                    userId, request.getVideoStyleType(), request.getVideoStyleValue());
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
        // 风格名称 + 风格值直接取前端传入值落库，不从字典/枚举二次获取
        project.setVideoStyleType(request.getVideoStyleType());
        project.setVideoStyleValue(request.getVideoStyleValue());
        project.setDefaultGenMode(request.getDefaultGenMode());
        project.setDefaultCreationMode(request.getDefaultCreationMode());
        // 初始化当前步骤：电影从1开始，剧集固定-1（步骤由episode表管理）
        project.setCurrentStep(isMovie ? 1 : -1);
        // 默认状态为草稿
        project.setStatus(0);
        // 默认不公开
        project.setIsPublic("0");
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
    public AidComicProject updateUserProject(UserProjectUpdateRequest request, Long userId)
    {
        // 先查询并校验归属
        AidComicProject project = this.selectUserProjectById(request.getId(), userId);
        if (project == null) {
            throw new RuntimeException("项目不存在或无权限操作");
        }
        // 公开锁：公开期间展示信息（名称/介绍/封面）随时可改，内容参数字段仍锁定
        boolean touchingContentFields = request.getAspectRatio() != null || request.getScriptType() != null
                || request.getVideoStyleType() != null || request.getVideoStyleValue() != null
                || request.getDefaultGenMode() != null || request.getDefaultCreationMode() != null;
        if (touchingContentFields) {
            projectContentGuardService.assertProjectEditable(project);
        }
        // 剧集类型且已有集数时，四个字段不可修改，直接忽略
        boolean isSeries = ProjectTypeEnum.SERIES.getValue().equals(project.getProjectType());
        boolean hasEpisodes = false;
        if (isSeries) {
            long episodeCount = aidComicEpisodeService.count(
                    Wrappers.<AidComicEpisode>lambdaQuery()
                            .eq(AidComicEpisode::getProjectId, project.getId())
                            .eq(AidComicEpisode::getDelFlag, "0"));
            hasEpisodes = episodeCount > 0;
        }
        boolean lockFields = isSeries && hasEpisodes;
        if (lockFields) {
            // 剧集已创建集数，画面比例/剧本类型/视频风格等内容参数不允许修改
            if (request.getAspectRatio() != null || request.getScriptType() != null
                    || request.getVideoStyleType() != null || request.getVideoStyleValue() != null) {
                log.info("项目已创建剧集，内容参数禁止修改, projectId={}", request.getId());
                throw new ServiceException("已建集不可改");
            }
            validateEnumFields(null, null, null,
                    request.getDefaultGenMode(), request.getDefaultCreationMode());
        } else {
            validateEnumFields(null, request.getAspectRatio(), request.getScriptType(),
                    request.getDefaultGenMode(), request.getDefaultCreationMode());
        }
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
        // 更新字段
        boolean needClearGenConfig = false; // 创作模式跨组切换时置 true，保存成功后清空项目级生成配置
        if (StringUtils.isNotEmpty(request.getProjectName())) {
            project.setProjectName(request.getProjectName());
        }
        if (request.getProjectDesc() != null) {
            project.setProjectDesc(request.getProjectDesc());
        }
        if (request.getCoverUrl() != null) {
            // 封面不得复用风格图
            assertCoverNotStyleAsset(request.getCoverUrl(), userId);
            project.setCoverUrl(request.getCoverUrl());
        }
        // 剧集类型且已有集数时 aspectRatio、scriptType、videoStyleType、videoStyleValue 不可修改，直接忽略
        if (!lockFields) {
            if (request.getAspectRatio() != null) {
                project.setAspectRatio(request.getAspectRatio());
            }
            if (request.getScriptType() != null) {
                project.setScriptType(request.getScriptType());
            }
            // 风格修改必须成对：只要请求里出现 videoStyleType 或 videoStyleValue 任一字段，
            // 就视为本次要改风格，两个字段必须同时传、同时非空，且与 type 语义匹配，避免出现
            // type=custom 但 value 仍是上次的中文提示词这种半更新不一致状态。
            boolean touchStyleType = request.getVideoStyleType() != null;
            boolean touchStyleValue = request.getVideoStyleValue() != null;
            if (touchStyleType || touchStyleValue) {
                String newStyleType = request.getVideoStyleType();
                String newStyleValue = request.getVideoStyleValue();
                if (StrUtil.isBlank(newStyleType) || StrUtil.isBlank(newStyleValue)) {
                    log.info("修改项目拒绝：风格类型与风格值必须同时传且非空: projectId={}, styleType={}, styleValue={}",
                            project.getId(), newStyleType, newStyleValue);
                    throw new ServiceException("请选择风格");
                }
                // 风格名称 + 风格值直接取前端传入值落库，不做格式校验、不从字典/枚举二次获取
                project.setVideoStyleType(newStyleType);
                project.setVideoStyleValue(newStyleValue);
            }
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
        project.setUpdateTime(DateUtils.getNowDate());
        project.setUpdateBy(String.valueOf(userId));
        // 剧本类型 × 创作模式兼容性（用合并后的最终值校验）：专业版/宫格不支持真人解说，直接拒绝
        validateScriptTypeCreationModeCompat(project.getScriptType(), project.getDefaultCreationMode());
        aidComicProjectService.updateById(project);
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

    /**
     * 用户提交项目审核（带归属校验）
     * 状态机：除「审核中(3)」「审核通过(4)」外的状态均可提交（成片导出成功后状态自动变为「完成未提交(2)」即可提审）
     * → 置为「审核中(3)」并清空状态原因，同时写入一条「提交审核」流水。
     *
     * @param id 项目ID
     * @param userId 用户ID
     * @return 提交审核后的项目
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AidComicProject submitAudit(Long id, Long userId)
    {
        // 查询并校验归属
        AidComicProject project = this.selectUserProjectById(id, userId);
        if (project == null) {
            log.info("提交项目审核失败，项目不存在或无权限: projectId={}, userId={}", id, userId);
            throw new ServiceException("项目不存在");
        }
        Integer beforeStatus = project.getStatus();
        // 校验当前状态是否允许提交审核
        if (Objects.equals(beforeStatus, ProjectStatusEnum.AUDITING.getValue())) {
            throw new ServiceException("内容审核中");
        }
        if (Objects.equals(beforeStatus, ProjectStatusEnum.AUDIT_PASSED.getValue())
                && !hasPendingExportVideo(project)) {
            // 已过审且没有待审新片 → 无重审必要；有待审新片（重新导出的成片）则放行重新提审，
            // 审核期间公开广场继续展示旧片（公开口径 status∈(3,4)），过审后新片自动转正
            throw new ServiceException("已通过审核");
        }
        // 内容前置校验：电影必须已有成品视频；剧集必须至少有一集审核通过
        checkProjectSubmitContent(project);
        // 置为审核中并清空上次状态原因
        Integer afterStatus = ProjectStatusEnum.AUDITING.getValue();
        LambdaUpdateWrapper<AidComicProject> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidComicProject::getId, id);
        updateWrapper.eq(AidComicProject::getUserId, userId);
        updateWrapper.set(AidComicProject::getStatus, afterStatus);
        updateWrapper.set(AidComicProject::getStatusReason, null);
        updateWrapper.set(AidComicProject::getUpdateTime, DateUtils.getNowDate());
        aidComicProjectService.update(updateWrapper);
        // 写入审核流水（提交审核），操作人记为用户ID
        aidComicAuditRecordService.saveAuditRecord(AuditTargetTypeEnum.PROJECT.getValue(), id, userId,
                AuditActionEnum.SUBMIT.getValue(), beforeStatus, afterStatus, null, String.valueOf(userId));
        // 微信公众号推送：提交审核（推送服务内部吞异常，不影响提审主流程）
        wechatNotifyService.notifyContentAudit(AuditTargetTypeEnum.PROJECT.getValue(), id,
                com.aid.notify.wechat.service.IWechatNotifyService.AUDIT_EVENT_SUBMITTED, null);
        // 回填最新状态返回
        project.setStatus(afterStatus);
        project.setStatusReason(null);
        return project;
    }

    /**
     * 用户公开项目（带归属校验）
     * 前提：项目必须为「审核通过(4)」状态；公开后 is_public 置为 1。
     *
     * @param id 项目ID
     * @param userId 用户ID
     * @return 公开后的项目
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AidComicProject publishProject(Long id, Long userId)
    {
        // 查询并校验归属
        AidComicProject project = this.selectUserProjectById(id, userId);
        if (project == null) {
            log.info("公开项目失败，项目不存在或无权限: projectId={}, userId={}", id, userId);
            throw new ServiceException("项目不存在");
        }
        // 公开前提：必须审核通过
        if (!Objects.equals(project.getStatus(), ProjectStatusEnum.AUDIT_PASSED.getValue())) {
            log.info("公开项目失败，项目未审核通过: projectId={}, status={}", id, project.getStatus());
            throw new ServiceException("请先通过审核");
        }
        // 已公开则幂等返回
        if (Objects.equals(IS_PUBLIC_YES, project.getIsPublic())) {
            return project;
        }
        // 发布权限校验：用户显式禁发 > 白名单豁免 > 发布总开关
        publishPermissionService.assertCanPublish(userId);
        // 置为公开并记录发布时间
        LambdaUpdateWrapper<AidComicProject> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidComicProject::getId, id);
        updateWrapper.eq(AidComicProject::getUserId, userId);
        updateWrapper.set(AidComicProject::getIsPublic, IS_PUBLIC_YES);
        updateWrapper.set(AidComicProject::getPublishTime, DateUtils.getNowDate());
        updateWrapper.set(AidComicProject::getUpdateTime, DateUtils.getNowDate());
        updateWrapper.set(AidComicProject::getUpdateBy, String.valueOf(userId));
        aidComicProjectService.update(updateWrapper);
        // 微信公众号推送：发布成功（推送服务内部吞异常，不影响发布主流程）
        wechatNotifyService.notifyContentAudit(AuditTargetTypeEnum.PROJECT.getValue(), id,
                com.aid.notify.wechat.service.IWechatNotifyService.AUDIT_EVENT_PUBLISHED, null);
        // 回填返回
        project.setIsPublic(IS_PUBLIC_YES);
        project.setPublishTime(DateUtils.getNowDate());
        return project;
    }

    /**
     * 用户关闭项目公开（带归属校验）
     * is_public 置回 0，项目从公开列表下架，内容恢复可修改；审核状态（status）保持不变，
     * 可直接再次公开（status 仍为 4）；重新导出的新成片进入待审槽，重新过审后转正。
     * 未公开时幂等返回。
     *
     * @param id 项目ID
     * @param userId 用户ID
     * @return 关闭公开后的项目
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AidComicProject unpublishProject(Long id, Long userId)
    {
        // 查询并校验归属
        AidComicProject project = this.selectUserProjectById(id, userId);
        if (project == null) {
            log.info("关闭项目公开失败，项目不存在或无权限: projectId={}, userId={}", id, userId);
            throw new ServiceException("项目不存在");
        }
        // 未公开则幂等返回
        if (!Objects.equals(IS_PUBLIC_YES, project.getIsPublic())) {
            return project;
        }
        // 置回未公开
        LambdaUpdateWrapper<AidComicProject> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidComicProject::getId, id);
        updateWrapper.eq(AidComicProject::getUserId, userId);
        updateWrapper.set(AidComicProject::getIsPublic, IS_PUBLIC_NO);
        updateWrapper.set(AidComicProject::getUpdateTime, DateUtils.getNowDate());
        updateWrapper.set(AidComicProject::getUpdateBy, String.valueOf(userId));
        aidComicProjectService.update(updateWrapper);
        // 回填返回
        project.setIsPublic(IS_PUBLIC_NO);
        return project;
    }

    /**
     * 判断电影项目是否存在待审核新成片（重新导出产生，pending_video_url 非空）。
     * 仅电影类型有项目级成片；剧集类型项目的成片挂在各集上，项目层面恒返回 false。
     *
     * @param project 项目实体
     * @return true=有待审新片，允许已过审状态重新提审
     */
    private boolean hasPendingExportVideo(AidComicProject project)
    {
        if (!Objects.equals(ProjectTypeEnum.MOVIE.getValue(), project.getProjectType())) {
            return false;
        }
        // 查询字段精简：仅需待审片字段（新增使用字段时此处必须同步补充）；多行时取最新一条
        AidEpisodeEditor editor = aidEpisodeEditorService.getOne(Wrappers.<AidEpisodeEditor>lambdaQuery()
                .select(AidEpisodeEditor::getId, AidEpisodeEditor::getPendingVideoUrl)
                .eq(AidEpisodeEditor::getProjectId, project.getId())
                .eq(AidEpisodeEditor::getEpisodeId, MOVIE_EPISODE_ID)
                .eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL)
                .orderByDesc(AidEpisodeEditor::getId)
                .last("LIMIT 1"));
        return Objects.nonNull(editor) && StringUtils.isNotEmpty(editor.getPendingVideoUrl());
    }

    /**
     * 提交项目审核的内容前置校验
     * 电影类型：项目必须已有成品视频（aid_episode_editor.final_video_url 非空，episode_id=0），否则报 请先合成视频
     * 剧集类型：项目下必须至少有一集审核通过（episode.status=4），否则报 无过审剧集
     *
     * @param project 项目实体
     */
    private void checkProjectSubmitContent(AidComicProject project)
    {
        // 电影：直接校验项目级成品视频（episode_id=0）；通过即可进入审核，审核通过后公开。
        // 并发建档可能产生多行，取最新一条（与受理侧 selectLatestEditor 口径一致）
        if (Objects.equals(ProjectTypeEnum.MOVIE.getValue(), project.getProjectType())) {
            AidEpisodeEditor editor = aidEpisodeEditorService.getOne(Wrappers.<AidEpisodeEditor>lambdaQuery()
                    .eq(AidEpisodeEditor::getProjectId, project.getId())
                    .eq(AidEpisodeEditor::getEpisodeId, MOVIE_EPISODE_ID)
                    .eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL)
                    .orderByDesc(AidEpisodeEditor::getId)
                    .last("LIMIT 1"));
            if (Objects.isNull(editor) || StringUtils.isEmpty(editor.getFinalVideoUrl())) {
                log.info("提交项目审核失败，电影缺成品视频: projectId={}", project.getId());
                throw new ServiceException("请先合成视频");
            }
            return;
        }
        // 剧集：校验项目下是否至少有一集审核通过
        long passedCount = aidComicEpisodeService.count(Wrappers.<AidComicEpisode>lambdaQuery()
                .eq(AidComicEpisode::getProjectId, project.getId())
                .eq(AidComicEpisode::getStatus, EpisodeStatusEnum.AUDIT_PASSED.getValue())
                .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL));
        if (passedCount <= 0) {
            log.info("提交项目审核失败，无审核通过的分集: projectId={}", project.getId());
            throw new ServiceException("暂无过审剧集");
        }
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
            // 查询字段精简：成片展示只需 id/项目/成片地址/待审片/导出状态（新增 VO 字段时此处必须同步补充）
            // 按 id 升序遍历后写覆盖，同一项目多条剪辑记录时保留最新一条
            for (AidEpisodeEditor editor : aidEpisodeEditorService.list(Wrappers.<AidEpisodeEditor>lambdaQuery()
                    .select(AidEpisodeEditor::getId, AidEpisodeEditor::getProjectId,
                            AidEpisodeEditor::getFinalVideoUrl, AidEpisodeEditor::getPendingVideoUrl,
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
        for (AidComicProject project : projects) {
            result.add(buildProjectVO(project, editorMap.get(project.getId()),
                    resolveEpisodeCount(project, episodeCountMap)));
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
     * @return 项目 VO
     */
    private UserProjectVO buildProjectVO(AidComicProject project, AidEpisodeEditor editor, Long episodeCount)
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
                .defaultGenMode(project.getDefaultGenMode())
                .defaultCreationMode(project.getDefaultCreationMode())
                .status(project.getStatus())
                .statusReason(project.getStatusReason())
                .isPublic(project.getIsPublic())
                .episodeCount(episodeCount)
                .createTime(project.getCreateTime())
                .updateTime(project.getUpdateTime());
        if (editor != null) {
            builder.episodeEditorId(editor.getId())
                    .finalVideoUrl(editor.getFinalVideoUrl())
                    .pendingVideoUrl(editor.getPendingVideoUrl())
                    .exportStatus(editor.getExportStatus());
        }
        return builder.build();
    }
}
