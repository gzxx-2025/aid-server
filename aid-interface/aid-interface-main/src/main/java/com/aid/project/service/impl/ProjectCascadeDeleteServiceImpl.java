package com.aid.project.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAudioAsset;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.domain.AidScenePlot;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidAudioAssetService;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidComicScriptService;
import com.aid.aid.service.IAidEpisodeEditorService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.aid.service.IAidScenePlotService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.media.cleanup.IGenerationArtifactCleanupService;
import com.aid.media.cleanup.IMediaOssCleanupService;
import com.aid.project.service.IProjectCascadeDeleteService;
import com.aid.projectgenconfig.service.IProjectGenConfigService;

import lombok.extern.slf4j.Slf4j;

/**
 * 项目级联删除服务实现。
 * 删除顺序：先清理各表 OSS 文件（本地必删成功、远程异步），再物理删库；最后删项目本身。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class ProjectCascadeDeleteServiceImpl implements IProjectCascadeDeleteService
{
    /** 生成产物级联清理（gen_record / audio_record + OSS） */
    @Autowired
    private IGenerationArtifactCleanupService generationArtifactCleanupService;

    /** OSS 文件清理服务 */
    @Autowired
    private IMediaOssCleanupService mediaOssCleanupService;

    /** 成片导出记录 */
    @Autowired
    private IAidEpisodeEditorService aidEpisodeEditorService;

    /** 分镜 */
    @Autowired
    private IAidStoryboardService aidStoryboardService;

    /** 剧集 */
    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;

    /** 剧本 */
    @Autowired
    private IAidComicScriptService aidComicScriptService;

    /** RPS 形态图 */
    @Autowired
    private IAidRolePropSceneFormImageService rpsFormImageService;

    /** RPS 形态 */
    @Autowired
    private IAidRolePropSceneFormService rpsFormService;

    /** RPS 主资产（角色/道具/场景） */
    @Autowired
    private IAidRolePropSceneService rpsService;

    /** 场次 */
    @Autowired
    private IAidScenePlotService scenePlotService;

    /** 角色音色绑定 */
    @Autowired
    private IAidRoleVoiceBindingService roleVoiceBindingService;

    /** 资产提取任务 */
    @Autowired
    private IAidExtractTaskService extractTaskService;

    /** 音频资产 */
    @Autowired
    private IAidAudioAssetService audioAssetService;

    /** 项目生成配置 */
    @Autowired
    private IProjectGenConfigService projectGenConfigService;

    /** 项目 */
    @Autowired
    private IAidComicProjectService aidComicProjectService;

    /**
     * 级联硬删除整个项目子树（含 OSS 清理）。
     *
     * @param projectId 项目ID
     * @param userId    操作用户ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProjectCascade(Long projectId, Long userId)
    {
        if (Objects.isNull(projectId))
        {
            return;
        }
        // 统一收集本次需清理的 OSS 文件，所有 DB 删除完成后再登记清理（afterCommit 后台执行，回滚则不动文件）
        List<String> filesToClean = new ArrayList<>();

        generationArtifactCleanupService.cleanupByProject(projectId);

        List<AidEpisodeEditor> editors = aidEpisodeEditorService.list(Wrappers.<AidEpisodeEditor>lambdaQuery()
                .select(AidEpisodeEditor::getId, AidEpisodeEditor::getFinalVideoUrl, AidEpisodeEditor::getCoverUrl)
                .eq(AidEpisodeEditor::getProjectId, projectId));
        if (!editors.isEmpty())
        {
            for (AidEpisodeEditor editor : editors)
            {
                filesToClean.add(editor.getFinalVideoUrl());
                filesToClean.add(editor.getCoverUrl());
            }
            aidEpisodeEditorService.remove(Wrappers.<AidEpisodeEditor>lambdaQuery()
                    .eq(AidEpisodeEditor::getProjectId, projectId));
        }

        aidStoryboardService.remove(Wrappers.<AidStoryboard>lambdaQuery()
                .eq(AidStoryboard::getProjectId, projectId));

        List<AidComicEpisode> episodes = aidComicEpisodeService.list(Wrappers.<AidComicEpisode>lambdaQuery()
                .select(AidComicEpisode::getId, AidComicEpisode::getComicCoverUrl)
                .eq(AidComicEpisode::getProjectId, projectId));
        if (!episodes.isEmpty())
        {
            for (AidComicEpisode ep : episodes)
            {
                filesToClean.add(ep.getComicCoverUrl());
            }
            aidComicEpisodeService.remove(Wrappers.<AidComicEpisode>lambdaQuery()
                    .eq(AidComicEpisode::getProjectId, projectId));
        }

        aidComicScriptService.remove(Wrappers.<AidComicScript>lambdaQuery()
                .eq(AidComicScript::getProjectId, projectId));

        List<AidRolePropSceneFormImage> formImages = rpsFormImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getImageUrl)
                        .eq(AidRolePropSceneFormImage::getProjectId, projectId));
        if (!formImages.isEmpty())
        {
            for (AidRolePropSceneFormImage img : formImages)
            {
                filesToClean.add(img.getImageUrl());
            }
            rpsFormImageService.remove(Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                    .eq(AidRolePropSceneFormImage::getProjectId, projectId));
        }

        rpsFormService.remove(Wrappers.<AidRolePropSceneForm>lambdaQuery()
                .eq(AidRolePropSceneForm::getProjectId, projectId));
        rpsService.remove(Wrappers.<AidRolePropScene>lambdaQuery()
                .eq(AidRolePropScene::getProjectId, projectId));
        scenePlotService.remove(Wrappers.<AidScenePlot>lambdaQuery()
                .eq(AidScenePlot::getProjectId, projectId));
        roleVoiceBindingService.remove(Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                .eq(AidRoleVoiceBinding::getProjectId, projectId));

        extractTaskService.remove(Wrappers.<AidExtractTask>lambdaQuery()
                .eq(AidExtractTask::getProjectId, projectId));

        List<AidAudioAsset> audioAssets = audioAssetService.list(Wrappers.<AidAudioAsset>lambdaQuery()
                .select(AidAudioAsset::getId, AidAudioAsset::getAudioUrl)
                .eq(AidAudioAsset::getProjectId, projectId));
        if (!audioAssets.isEmpty())
        {
            for (AidAudioAsset a : audioAssets)
            {
                filesToClean.add(a.getAudioUrl());
            }
            audioAssetService.remove(Wrappers.<AidAudioAsset>lambdaQuery()
                    .eq(AidAudioAsset::getProjectId, projectId));
        }

        projectGenConfigService.clearProjectConfig(projectId, userId);

        AidComicProject project = aidComicProjectService.getById(projectId);
        if (Objects.nonNull(project))
        {
            filesToClean.add(project.getCoverUrl());
        }
        aidComicProjectService.removeById(projectId);

        mediaOssCleanupService.cleanupFiles(filesToClean);

        log.info("项目级联硬删除完成, projectId={}, userId={}", projectId, userId);
    }
}
