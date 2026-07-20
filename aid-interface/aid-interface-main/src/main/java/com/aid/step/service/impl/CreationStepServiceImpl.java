package com.aid.step.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidScenePlot;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidComicScriptService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidScenePlotService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.enums.CreationStepEnum;
import com.aid.enums.ProjectTypeEnum;
import com.aid.step.service.ICreationStepService;
import com.aid.step.vo.StepStatusVO;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 创作流水线步骤Service实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class CreationStepServiceImpl implements ICreationStepService {

    private static final String DEL_FLAG_NORMAL = "0";

    @Autowired
    private IAidComicProjectService aidComicProjectService;
    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;
    @Autowired
    private IAidComicScriptService aidComicScriptService;
    @Autowired
    private IAidRolePropSceneService rpsService;
    @Autowired
    private IAidScenePlotService scenePlotService;
    @Autowired
    private IAidStoryboardService aidStoryboardService;
    @Override
    public StepStatusVO getStepStatus(Long projectId, Long episodeId, Long userId) {
        int currentStep = getCurrentStep(projectId, episodeId, userId);

        // 构建7个步骤的详细状态
        List<StepStatusVO.StepDetail> steps = new ArrayList<>();
        for (CreationStepEnum stepEnum : CreationStepEnum.values()) {
            int stepVal = stepEnum.getValue();
            // 跳过-1(剧集默认)
            if (stepVal < 1) {
                continue;
            }
            String status;
            if (stepVal < currentStep) {
                status = "completed";
            } else if (stepVal == currentStep) {
                status = "current";
            } else {
                status = "waiting";
            }
            steps.add(StepStatusVO.StepDetail.builder()
                    .step(stepVal)
                    .name(stepEnum.getDesc())
                    .status(status)
                    .build());
        }

        return StepStatusVO.builder()
                .currentStep(currentStep)
                .steps(steps)
                .build();
    }
    @Override
    public void checkStepUnlocked(Long projectId, Long episodeId, Long userId, int requiredStep) {
        int currentStep = getCurrentStep(projectId, episodeId, userId);
        if (currentStep < requiredStep) {
            CreationStepEnum required = CreationStepEnum.getByValue(requiredStep);
            String stepName = (required != null) ? required.getDesc() : ("步骤" + requiredStep);
            // 找到上一步的名称提示用户
            CreationStepEnum prev = CreationStepEnum.getByValue(requiredStep - 1);
            String prevName = (prev != null) ? prev.getDesc() : ("步骤" + (requiredStep - 1));
            log.info("步骤未解锁, projectId={}, episodeId={}, current={}, required={}", projectId, episodeId, currentStep, requiredStep);
            throw new ServiceException("请先完成" + prevName);
        }
    }
    @Override
    public void tryAdvanceStepQuietly(Long projectId, Long episodeId, Long userId, int completedStep) {
        try {
            tryAdvanceStep(projectId, episodeId, userId, completedStep);
        } catch (ServiceException e) {
            // 附带副作用调用：步骤已推进/未匹配/完成条件未满足等均不影响主流程，吞掉仅记录日志
            log.info("静默推进步骤跳过, projectId={}, episodeId={}, completedStep={}, reason={}",
                    projectId, episodeId, completedStep, e.getMessage());
        }
    }

    @Override
    public void tryAdvanceStep(Long projectId, Long episodeId, Long userId, int completedStep) {
        AidComicProject project = getProjectWithCheck(projectId, userId);
        boolean isMovie = Objects.equals(ProjectTypeEnum.MOVIE.getValue(), project.getProjectType());

        if (isMovie) {
            tryAdvanceMovieStep(projectId, userId, completedStep);
        } else {
            tryAdvanceSeriesStep(projectId, episodeId, userId, completedStep);
        }
    }

    /**
     * 剧集：推进步骤。
     */
    private void tryAdvanceSeriesStep(Long projectId, Long episodeId, Long userId, int completedStep) {
        if (Objects.isNull(episodeId) || episodeId <= 0) {
            log.error("剧集模式缺少episodeId, projectId={}", projectId);
            throw new ServiceException("请选择集数");
        }

        AidComicEpisode episode = getEpisodeWithCheck(episodeId, projectId, userId);
        int currentStep = episode.getCurrentStep() != null ? episode.getCurrentStep() : 1;
        currentStep = Math.max(currentStep, 1);

        // 如果当前步骤已完成，说明用户在回溯编辑，提示已推进
        if (currentStep > completedStep) {
            log.info("剧集-该步骤已推进, projectId={}, episodeId={}, currentStep={}, completedStep={}",
                    projectId, episodeId, currentStep, completedStep);
            throw new ServiceException("该步骤已推进");
        }

        // 只有当完成的步骤 == 当前步骤时才推进(防止重复推进)
        if (currentStep != completedStep) {
            log.info("剧集-步骤状态不一致, projectId={}, episodeId={}, currentStep={}, completedStep={}",
                    projectId, episodeId, currentStep, completedStep);
            throw new ServiceException("请先完成第" + currentStep + "步");
        }

        // 检查该步骤的完成条件（不满足会直接抛出带明确提示的异常）
        checkSeriesStepCompletionOrThrow(projectId, episodeId, userId, completedStep);
        // 推进到下一步(最大为7)
        int nextStep = Math.min(completedStep + 1, CreationStepEnum.PREVIEW.getValue());
        episode.setCurrentStep(nextStep);
        episode.setUpdateTime(DateUtils.getNowDate());
        aidComicEpisodeService.updateById(episode);
        log.info("剧集-步骤推进成功, projectId={}, episodeId={}, from={}, to={}", projectId, episodeId, completedStep, nextStep);
    }

    /**
     * 电影：推进步骤。
     */
    private void tryAdvanceMovieStep(Long projectId, Long userId, int completedStep) {
        AidComicProject project = getProjectWithCheck(projectId, userId);
        int currentStep = project.getCurrentStep() != null ? project.getCurrentStep() : 1;
        currentStep = Math.max(currentStep, 1);

        // 如果当前步骤已完成，说明用户在回溯编辑，提示已推进
        if (currentStep > completedStep) {
            log.info("电影-该步骤已推进, projectId={}, currentStep={}, completedStep={}",
                    projectId, currentStep, completedStep);
            throw new ServiceException("该步骤已推进");
        }

        // 只有当完成的步骤 == 当前步骤时才推进(防止重复推进)
        if (currentStep != completedStep) {
            log.info("电影-步骤状态不一致, projectId={}, currentStep={}, completedStep={}",
                    projectId, currentStep, completedStep);
            throw new ServiceException("请先完成第" + currentStep + "步");
        }

        // 检查该步骤的完成条件（不满足会直接抛出带明确提示的异常）
        checkMovieStepCompletionOrThrow(projectId, userId, completedStep);
        // 推进到下一步(最大为7)
        int nextStep = Math.min(completedStep + 1, CreationStepEnum.PREVIEW.getValue());
        project.setCurrentStep(nextStep);
        project.setUpdateTime(DateUtils.getNowDate());
        aidComicProjectService.updateById(project);
        log.info("电影-步骤推进成功, projectId={}, from={}, to={}", projectId, completedStep, nextStep);
    }
    /**
     * 获取当前步骤值。
     */
    private int getCurrentStep(Long projectId, Long episodeId, Long userId) {
        AidComicProject project = getProjectWithCheck(projectId, userId);
        boolean isMovie = Objects.equals(ProjectTypeEnum.MOVIE.getValue(), project.getProjectType());

        if (isMovie) {
            // 电影：步骤存在project表
            int step = project.getCurrentStep() != null ? project.getCurrentStep() : 1;
            return Math.max(step, 1);
        } else {
            // 剧集：步骤存在episode表
            if (Objects.isNull(episodeId) || episodeId <= 0) {
                log.error("剧集模式缺少episodeId, projectId={}", projectId);
                throw new ServiceException("请选择集数");
            }
            AidComicEpisode episode = getEpisodeWithCheck(episodeId, projectId, userId);
            int step = episode.getCurrentStep() != null ? episode.getCurrentStep() : 1;
            return Math.max(step, 1);
        }
    }
    /**
     * 剧集：检查指定步骤的完成条件，不满足则抛出带明确提示的业务异常。
     */
    private void checkSeriesStepCompletionOrThrow(Long projectId, Long episodeId, Long userId, int step) {
        switch (step) {
            case 1:
                checkSeriesGlobalSettingOrThrow(projectId);
                break;
            case 2:
                checkSeriesScriptOrThrow(projectId, episodeId, userId);
                break;
            case 3:
                checkSeriesAssetOrThrow(projectId, episodeId, userId);
                break;
            case 4:
                checkSeriesStoryboardOrThrow(projectId, episodeId, userId);
                break;
            case 5:
                checkSeriesVideoSelectedOrThrow(projectId, episodeId, userId);
                break;
            case 6:
                checkSeriesAudioSelectedOrThrow(projectId, episodeId, userId);
                break;
            case 7:
                // 视频预览：终态，无需校验
                break;
            default:
                throw new ServiceException("无效的步骤编号：" + step);
        }
    }

    /**
     * 剧集-步骤1：全局设定 —— 画面比例、剧本类型、视频风格来源均已填写。
     */
    private void checkSeriesGlobalSettingOrThrow(Long projectId) {
        AidComicProject project = aidComicProjectService.getById(projectId);
        if (Objects.isNull(project)) {
            throw new ServiceException("项目不存在");
        }
        if (StrUtil.isBlank(project.getAspectRatio())) {
            throw new ServiceException("请先设置画面比例");
        }
        if (StrUtil.isBlank(project.getScriptType())) {
            throw new ServiceException("请先设置剧本类型");
        }
        if (StrUtil.isBlank(project.getVideoStyleType())) {
            throw new ServiceException("请先设置视频风格");
        }
    }

    /**
     * 剧集-步骤2：故事剧本 —— 至少有一条剧本且原文非空（按当前用户隔离）。
     */
    private void checkSeriesScriptOrThrow(Long projectId, Long episodeId, Long userId) {
        LambdaQueryWrapper<AidComicScript> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidComicScript::getProjectId, projectId)
               .eq(AidComicScript::getEpisodeId, episodeId)
               .eq(AidComicScript::getUserId, userId)
               .eq(AidComicScript::getDelFlag, DEL_FLAG_NORMAL)
               .isNotNull(AidComicScript::getOriginalText)
               .ne(AidComicScript::getOriginalText, "")
               .last("LIMIT 1");
        if (aidComicScriptService.count(wrapper) == 0) {
            throw new ServiceException("请先编写或上传剧本");
        }
    }

    /**
     * 剧集-步骤3：场景角色道具 —— 至少包含1个角色和1个本集可用场景（按当前用户隔离）。
     * 角色按项目级校验（剧集角色主资产项目内唯一，episodeId=0）；
     * 场景按「本集直属场景 ∪ 本集剧情引用（aid_scene_plot）的复用场景」校验。
     */
    private void checkSeriesAssetOrThrow(Long projectId, Long episodeId, Long userId) {
        // 校验角色（项目级：全局角色与历史按集角色均可满足）
        if (Objects.isNull(rpsService.getOne(Wrappers.<AidRolePropScene>lambdaQuery()
                .select(AidRolePropScene::getId)
                .eq(AidRolePropScene::getProjectId, projectId)
                .eq(AidRolePropScene::getUserId, userId)
                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                .eq(AidRolePropScene::getAssetType, "character")
                .last("LIMIT 1")))) {
            throw new ServiceException("请至少创建一个角色");
        }
        // 校验场景：本集直属场景，或本集剧情引用了复用场景（跨集复用时主资产归属其他集）
        boolean hasEpisodeScene = Objects.nonNull(rpsService.getOne(Wrappers.<AidRolePropScene>lambdaQuery()
                .select(AidRolePropScene::getId)
                .eq(AidRolePropScene::getProjectId, projectId)
                .eq(AidRolePropScene::getEpisodeId, episodeId)
                .eq(AidRolePropScene::getUserId, userId)
                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                .eq(AidRolePropScene::getAssetType, "scene")
                .last("LIMIT 1")));
        boolean hasPlotSceneRef = hasEpisodeScene || scenePlotService.count(
                Wrappers.<AidScenePlot>lambdaQuery()
                        .eq(AidScenePlot::getProjectId, projectId)
                        .eq(AidScenePlot::getEpisodeId, episodeId)
                        .eq(AidScenePlot::getUserId, userId)
                        .eq(AidScenePlot::getDelFlag, DEL_FLAG_NORMAL)
                        .isNotNull(AidScenePlot::getSceneId)) > 0;
        if (!hasPlotSceneRef) {
            throw new ServiceException("请至少创建一个场景");
        }
    }

    /**
     * 剧集-步骤4：分镜脚本 —— 总数>0（按当前用户隔离）。
     */
    private void checkSeriesStoryboardOrThrow(Long projectId, Long episodeId, Long userId) {
        LambdaQueryWrapper<AidStoryboard> totalWrapper = Wrappers.lambdaQuery();
        totalWrapper.eq(AidStoryboard::getProjectId, projectId)
                    .eq(AidStoryboard::getEpisodeId, episodeId)
                    .eq(AidStoryboard::getUserId, userId)
                    .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        long total = aidStoryboardService.count(totalWrapper);
        if (total == 0) {
            throw new ServiceException("请至少创建一条分镜");
        }
    }

    /**
     * 剧集-步骤5：分镜视频 —— 总数>0（不校验是否已选定视频，按当前用户隔离）。
     */
    private void checkSeriesVideoSelectedOrThrow(Long projectId, Long episodeId, Long userId) {
        LambdaQueryWrapper<AidStoryboard> totalWrapper = Wrappers.lambdaQuery();
        totalWrapper.eq(AidStoryboard::getProjectId, projectId)
                    .eq(AidStoryboard::getEpisodeId, episodeId)
                    .eq(AidStoryboard::getUserId, userId)
                    .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        long total = aidStoryboardService.count(totalWrapper);
        if (total == 0) {
            throw new ServiceException("请至少创建一条分镜");
        }
    }

    /**
     * 剧集-步骤6：配音对口型 —— 总数>0（不校验是否已选定配音，按当前用户隔离）。
     */
    private void checkSeriesAudioSelectedOrThrow(Long projectId, Long episodeId, Long userId) {
        LambdaQueryWrapper<AidStoryboard> totalWrapper = Wrappers.lambdaQuery();
        totalWrapper.eq(AidStoryboard::getProjectId, projectId)
                    .eq(AidStoryboard::getEpisodeId, episodeId)
                    .eq(AidStoryboard::getUserId, userId)
                    .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        long total = aidStoryboardService.count(totalWrapper);
        if (total == 0) {
            throw new ServiceException("请至少创建一条分镜");
        }
    }
    /**
     * 电影：检查指定步骤的完成条件，不满足则抛出带明确提示的业务异常。
     */
    private void checkMovieStepCompletionOrThrow(Long projectId, Long userId, int step) {
        switch (step) {
            case 1:
                checkMovieGlobalSettingOrThrow(projectId);
                break;
            case 2:
                checkMovieScriptOrThrow(projectId, userId);
                break;
            case 3:
                checkMovieAssetOrThrow(projectId, userId);
                break;
            case 4:
                checkMovieStoryboardOrThrow(projectId, userId);
                break;
            case 5:
                checkMovieVideoSelectedOrThrow(projectId, userId);
                break;
            case 6:
                checkMovieAudioSelectedOrThrow(projectId, userId);
                break;
            case 7:
                // 视频预览：终态，无需校验
                break;
            default:
                throw new ServiceException("无效的步骤编号：" + step);
        }
    }

    /**
     * 电影-步骤1：全局设定 —— 画面比例、剧本类型、视频风格来源均已填写。
     */
    private void checkMovieGlobalSettingOrThrow(Long projectId) {
        AidComicProject project = aidComicProjectService.getById(projectId);
        if (Objects.isNull(project)) {
            throw new ServiceException("项目不存在");
        }
        if (StrUtil.isBlank(project.getAspectRatio())) {
            throw new ServiceException("请先设置画面比例");
        }
        if (StrUtil.isBlank(project.getScriptType())) {
            throw new ServiceException("请先设置剧本类型");
        }
    }

    /**
     * 电影-步骤2：故事剧本 —— 至少有一条剧本且原文非空（按当前用户隔离）。
     */
    private void checkMovieScriptOrThrow(Long projectId, Long userId) {
        LambdaQueryWrapper<AidComicScript> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidComicScript::getProjectId, projectId)
               .eq(AidComicScript::getEpisodeId, 0L)
               .eq(AidComicScript::getUserId, userId)
               .eq(AidComicScript::getDelFlag, DEL_FLAG_NORMAL)
               .isNotNull(AidComicScript::getOriginalText)
               .ne(AidComicScript::getOriginalText, "")
               .last("LIMIT 1");
        if (aidComicScriptService.count(wrapper) == 0) {
            throw new ServiceException("请先编写或上传剧本");
        }
    }

    /**
     * 电影-步骤3：场景角色道具 —— 至少包含1个角色和1个场景（主表，episodeId=0，按当前用户隔离）。
     */
    private void checkMovieAssetOrThrow(Long projectId, Long userId) {
        // 校验角色
        if (Objects.isNull(rpsService.getOne(Wrappers.<AidRolePropScene>lambdaQuery()
                .select(AidRolePropScene::getId)
                .eq(AidRolePropScene::getProjectId, projectId)
                .eq(AidRolePropScene::getEpisodeId, 0L)
                .eq(AidRolePropScene::getUserId, userId)
                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                .eq(AidRolePropScene::getAssetType, "character")
                .last("LIMIT 1")))) {
            throw new ServiceException("请至少创建一个角色");
        }
        // 校验场景
        if (Objects.isNull(rpsService.getOne(Wrappers.<AidRolePropScene>lambdaQuery()
                .select(AidRolePropScene::getId)
                .eq(AidRolePropScene::getProjectId, projectId)
                .eq(AidRolePropScene::getEpisodeId, 0L)
                .eq(AidRolePropScene::getUserId, userId)
                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                .eq(AidRolePropScene::getAssetType, "scene")
                .last("LIMIT 1")))) {
            throw new ServiceException("请至少创建一个场景");
        }
    }

    /**
     * 电影-步骤4：分镜脚本 —— 总数>0（按当前用户隔离）。
     */
    private void checkMovieStoryboardOrThrow(Long projectId, Long userId) {
        LambdaQueryWrapper<AidStoryboard> totalWrapper = Wrappers.lambdaQuery();
        totalWrapper.eq(AidStoryboard::getProjectId, projectId)
                    .eq(AidStoryboard::getEpisodeId, 0L)
                    .eq(AidStoryboard::getUserId, userId)
                    .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        long total = aidStoryboardService.count(totalWrapper);
        if (total == 0) {
            throw new ServiceException("请至少创建一条分镜");
        }
    }

    /**
     * 电影-步骤5：分镜视频 —— 总数>0（不校验是否已选定视频，按当前用户隔离）。
     */
    private void checkMovieVideoSelectedOrThrow(Long projectId, Long userId) {
        LambdaQueryWrapper<AidStoryboard> totalWrapper = Wrappers.lambdaQuery();
        totalWrapper.eq(AidStoryboard::getProjectId, projectId)
                    .eq(AidStoryboard::getEpisodeId, 0L)
                    .eq(AidStoryboard::getUserId, userId)
                    .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        long total = aidStoryboardService.count(totalWrapper);
        if (total == 0) {
            throw new ServiceException("请至少创建一条分镜");
        }
    }

    /**
     * 电影-步骤6：配音对口型 —— 总数>0（不校验是否已选定配音，按当前用户隔离）。
     */
    private void checkMovieAudioSelectedOrThrow(Long projectId, Long userId) {
        LambdaQueryWrapper<AidStoryboard> totalWrapper = Wrappers.lambdaQuery();
        totalWrapper.eq(AidStoryboard::getProjectId, projectId)
                    .eq(AidStoryboard::getEpisodeId, 0L)
                    .eq(AidStoryboard::getUserId, userId)
                    .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        long total = aidStoryboardService.count(totalWrapper);
        if (total == 0) {
            throw new ServiceException("请至少创建一条分镜");
        }
    }

    /** 校验项目归属 */
    private AidComicProject getProjectWithCheck(Long projectId, Long userId) {
        AidComicProject project = aidComicProjectService.getById(projectId);
        if (Objects.isNull(project) || !Objects.equals(DEL_FLAG_NORMAL, project.getDelFlag())) {
            log.error("项目不存在, projectId={}", projectId);
            throw new ServiceException("项目不存在");
        }
        if (!Objects.equals(project.getUserId(), userId)) {
            log.error("无权操作项目, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("无权操作");
        }
        return project;
    }

    /** 校验剧集归属（含 userId 过滤，确保剧集属于当前用户） */
    private AidComicEpisode getEpisodeWithCheck(Long episodeId, Long projectId, Long userId) {
        LambdaQueryWrapper<AidComicEpisode> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidComicEpisode::getId, episodeId);
        wrapper.eq(AidComicEpisode::getProjectId, projectId);
        wrapper.eq(AidComicEpisode::getUserId, userId);
        wrapper.eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL);
        AidComicEpisode episode = aidComicEpisodeService.getOne(wrapper);
        if (Objects.isNull(episode)) {
            log.error("剧集不存在或不属于当前用户, episodeId={}, projectId={}, userId={}",
                    episodeId, projectId, userId);
            throw new ServiceException("剧集不存在");
        }
        return episode;
    }
}
