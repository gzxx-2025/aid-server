package com.aid.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidComicAsset;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidPromptLib;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidComicAssetService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.aid.service.IAidPromptLibService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.agent.AgentDefaultParamsApplier;
import com.aid.agent.AgentModelDefault;
import com.aid.agent.AgentModelResolver;
import com.aid.agent.AgentScene;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.service.IAiModelConfigService;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.StringUtils;
import com.aid.core.service.ISysUserService;
import com.aid.domain.dto.GenerationParams;
import com.aid.domain.dto.ImageGenReqDTO;
import com.aid.domain.dto.MediaGenRespDTO;
import com.aid.domain.dto.TextGenReqDTO;
import com.aid.domain.dto.VideoGenReqDTO;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.service.IMediaGenerationService;
import com.aid.service.IMediaGenerationBizService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 分镜媒体生成业务 Service 实现。
 *
 * @author 视觉AID
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class MediaGenerationBizServiceImpl implements IMediaGenerationBizService {

    private final IAidStoryboardService aidStoryboardService;
    private final IAidGenRecordService aidGenRecordService;
    private final IAidComicAssetService aidComicAssetService;
    private final IAidPromptLibService aidPromptLibService;
    private final IAidComicProjectService aidComicProjectService;
    private final ISysUserService sysUserService;
    private final IMediaGenerationService mediaGenerationService;
    /** Agent 默认模型解析器。 */
    private final AgentModelResolver agentModelResolver;
    /** Agent 默认参数应用器。 */
    private final AgentDefaultParamsApplier agentDefaultParamsApplier;
    /** AI 模型配置查询服务。 */
    private final IAiModelConfigService aiModelConfigService;

    /** 经济生成模式。 */
    private static final String GEN_MODE_ECONOMY = "economy";
    /** 性能生成模式。 */
    private static final String GEN_MODE_PERFORMANCE = "performance";

    /**
     * 生成分镜文本。
     *
     * @param dto 文本生成请求
     * @return 媒体生成响应
     */
    @Override
    public MediaGenRespDTO generateText(TextGenReqDTO dto) {
        validateTextReq(dto);
        AidStoryboard storyboard = validateAndGetStoryboard(dto.getStoryboardId(), dto.getUserId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", dto.getPrompt());
        payload.put("messages", dto.getMessages());
        payload.put("modelName", dto.getModelName());
        payload.put("storyboardId", storyboard.getId());
        payload.put("userId", dto.getUserId());
        String payloadJson = JSON.toJSONString(payload);

        AidGenRecord record = new AidGenRecord();
        record.setUserId(dto.getUserId());
        // 冗余项目与剧集维度，支持生成记录反查。
        record.setProjectId(storyboard.getProjectId());
        record.setEpisodeId(storyboard.getEpisodeId());
        record.setStoryboardId(dto.getStoryboardId());
        record.setGenType("text");
        record.setUserInputText(dto.getPrompt());
        record.setGenParams(payloadJson);
        record.setRemark(payloadJson);
        record.setStatus(0); // 处理中
        record.setCreateTime(DateUtils.getNowDate());
        record.setDelFlag("0");
        aidGenRecordService.insertAidGenRecord(record);

        log.info("分镜文本生成记录已创建, recordId={}, storyboardId={}, userId={}",
                record.getId(), dto.getStoryboardId(), dto.getUserId());

        MediaTextGenerateRequest textRequest = new MediaTextGenerateRequest();
        textRequest.setModelName(dto.getModelName());
        textRequest.setPrompt(dto.getPrompt());
        textRequest.setMessages(dto.getMessages());
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("localRecordId", record.getId());
        options.put("payloadSnapshot", payloadJson);
        textRequest.setOptions(options);

        MediaTaskResponse taskResponse;
        try {
            taskResponse = mediaGenerationService.generateText(textRequest);
        } catch (Exception ex) {
            // 媒体提交失败时显式标记记录失败，避免任务长期停留在处理中。
            log.error("分镜文本生成提交失败, recordId={}", record.getId(), ex);
            record.setStatus(2);
            record.setUpdateTime(DateUtils.getNowDate());
            aidGenRecordService.updateById(record);
            throw ex;
        }

        log.info("大模型文本生成任务已提交, recordId={}, taskId={}, status={}",
                record.getId(), taskResponse.getTaskId(), taskResponse.getStatus());

        if (taskResponse.getTaskId() != null) {
            record.setTaskId(taskResponse.getTaskId().toString());
        }
        // Provider 同步失败但未抛异常时，同步修正本地记录状态。
        if ("FAILED".equals(taskResponse.getStatus())) {
            record.setStatus(2);
        }
        record.setUpdateTime(DateUtils.getNowDate());
        aidGenRecordService.updateById(record);

        return MediaGenRespDTO.builder()
                .recordId(record.getId())
                .taskId(taskResponse.getTaskId() != null ? taskResponse.getTaskId().toString() : null)
                .status(taskResponse.getStatus())
                .build();
    }

    /**
     * 生成分镜图片。
     *
     * @param dto 图片生成请求
     * @return 媒体生成响应
     */
    @Override
    public MediaGenRespDTO generateImage(ImageGenReqDTO dto) {
        validateImageReq(dto);
        AidStoryboard storyboard = validateAndGetStoryboard(dto.getStoryboardId(), dto.getUserId());

        AidComicProject project = aidComicProjectService.getById(storyboard.getProjectId());
        if (project == null) {
            throw new ServiceException("项目不存在");
        }

        boolean isEconomyMode = GEN_MODE_ECONOMY.equals(project.getDefaultGenMode());
        validateGenParams(dto.getGenParams(), isEconomyMode, true);

        if (dto.getGenParams() == null || StringUtils.isEmpty(dto.getGenParams().getImagePrompt())) {
            throw new ServiceException("画面描述不能为空");
        }

        String finalPrompt = buildImageFinalPrompt(dto.getGenParams());

        List<String> referenceImages = fetchAllAssetUrlsFromGenParams(dto.getGenParams());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", finalPrompt);
        payload.put("referenceImages", referenceImages);
        if (dto.getGenParams() != null) {
            payload.put("shotSize", dto.getGenParams().getShotSize());
            payload.put("cameraAngle", dto.getGenParams().getCameraAngle());
            payload.put("colorTone", dto.getGenParams().getColorTone());
            payload.put("focalLength", dto.getGenParams().getFocalLength());
            payload.put("lighting", dto.getGenParams().getLighting());
            payload.put("exposureBlur", dto.getGenParams().getExposureBlur());
            payload.put("sceneIds", dto.getGenParams().getSceneIds());
            payload.put("characterIds", dto.getGenParams().getCharacterIds());
            payload.put("propIds", dto.getGenParams().getPropIds());
            payload.put("poseIds", dto.getGenParams().getPoseIds());
            payload.put("expressionIds", dto.getGenParams().getExpressionIds());
            payload.put("effectIds", dto.getGenParams().getEffectIds());
            payload.put("sketchIds", dto.getGenParams().getSketchIds());
        }
        payload.put("storyboardId", storyboard.getId());
        payload.put("userId", dto.getUserId());
        String payloadJson = JSON.toJSONString(payload);

        AidGenRecord record = new AidGenRecord();
        record.setUserId(dto.getUserId());
        // 冗余项目与剧集维度，支持生成记录反查。
        record.setProjectId(storyboard.getProjectId());
        record.setEpisodeId(storyboard.getEpisodeId());
        record.setStoryboardId(dto.getStoryboardId());
        record.setGenType("image");
        record.setUserInputText(dto.getGenParams() != null ? dto.getGenParams().getImagePrompt() : null);
        record.setGenParams(JSON.toJSONString(dto.getGenParams()));
        record.setRemark(payloadJson);
        record.setStatus(0); // 处理中
        record.setCreateTime(DateUtils.getNowDate());
        record.setDelFlag("0");
        aidGenRecordService.insertAidGenRecord(record);

        log.info("分镜图片生成记录已创建, recordId={}, storyboardId={}, userId={}",
                record.getId(), dto.getStoryboardId(), dto.getUserId());

        MediaImageGenerateRequest imageRequest = new MediaImageGenerateRequest();
        imageRequest.setPrompt(finalPrompt);
        // 顶层字段保留首张参考图，完整列表通过扩展参数传递。
        if (!referenceImages.isEmpty()) {
            imageRequest.setReferenceImageUrl(referenceImages.get(0));
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("referenceImages", referenceImages);
        options.put("localRecordId", record.getId());
        options.put("payloadSnapshot", payloadJson);
        imageRequest.setOptions(options);

        MediaTaskResponse taskResponse;
        try {
            taskResponse = mediaGenerationService.generateImage(imageRequest);
        } catch (Exception ex) {
            // 媒体提交失败时显式标记记录失败，避免任务长期停留在处理中。
            log.error("分镜生图提交失败, recordId={}", record.getId(), ex);
            record.setStatus(2);
            record.setUpdateTime(DateUtils.getNowDate());
            aidGenRecordService.updateById(record);
            throw ex;
        }

        log.info("大模型生图任务已提交, recordId={}, taskId={}, status={}",
                record.getId(), taskResponse.getTaskId(), taskResponse.getStatus());

        if (taskResponse.getTaskId() != null) {
            record.setTaskId(taskResponse.getTaskId().toString());
        }
        // Provider 同步失败但未抛异常时，同步修正本地记录状态。
        if ("FAILED".equals(taskResponse.getStatus())) {
            record.setStatus(2);
        }
        record.setUpdateTime(DateUtils.getNowDate());
        aidGenRecordService.updateById(record);

        return MediaGenRespDTO.builder()
                .recordId(record.getId())
                .taskId(taskResponse.getTaskId() != null ? taskResponse.getTaskId().toString() : null)
                .status(taskResponse.getStatus())
                .build();
    }

    /**
     * 生成分镜视频。
     *
     * @param dto 视频生成请求
     * @return 媒体生成响应
     */
    @Override
    public MediaGenRespDTO generateVideo(VideoGenReqDTO dto) {
        validateVideoReq(dto);
        AidStoryboard storyboard = validateAndGetStoryboard(dto.getStoryboardId(), dto.getUserId());

        AidComicProject project = aidComicProjectService.getById(storyboard.getProjectId());
        if (project == null) {
            throw new ServiceException("项目不存在");
        }

        boolean isEconomyMode = GEN_MODE_ECONOMY.equals(project.getDefaultGenMode());
        validateGenParams(dto.getGenParams(), isEconomyMode, false);

        AidGenRecord baseImageRecord = aidGenRecordService.selectAidGenRecordById(dto.getBaseImageRecordId());
        if (baseImageRecord == null) {
            throw new ServiceException("底图生成记录不存在, baseImageRecordId=" + dto.getBaseImageRecordId());
        }
        if (StringUtils.isEmpty(baseImageRecord.getFileUrl())) {
            throw new ServiceException("请等待底图生成完成");
        }
        String baseImageUrl = baseImageRecord.getFileUrl();

        if (dto.getGenParams() == null || StringUtils.isEmpty(dto.getGenParams().getVideoPrompt())) {
            throw new ServiceException("动作描述不能为空");
        }

        String finalPrompt = buildVideoFinalPrompt(dto.getGenParams());

        List<String> referenceImages = fetchAllAssetUrlsFromGenParams(dto.getGenParams());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", finalPrompt);
        payload.put("baseImageUrl", baseImageUrl);
        payload.put("referenceImages", referenceImages);
        if (dto.getGenParams() != null) {
            payload.put("cameraMovement", dto.getGenParams().getCameraMovement());
            payload.put("shootingTechnique", dto.getGenParams().getShootingTechnique());
            payload.put("sceneIds", dto.getGenParams().getSceneIds());
            payload.put("characterIds", dto.getGenParams().getCharacterIds());
            payload.put("propIds", dto.getGenParams().getPropIds());
            payload.put("poseIds", dto.getGenParams().getPoseIds());
            payload.put("expressionIds", dto.getGenParams().getExpressionIds());
            payload.put("effectIds", dto.getGenParams().getEffectIds());
            payload.put("sketchIds", dto.getGenParams().getSketchIds());
        }
        payload.put("storyboardId", storyboard.getId());
        payload.put("userId", dto.getUserId());
        payload.put("baseImageRecordId", dto.getBaseImageRecordId());
        String payloadJson = JSON.toJSONString(payload);

        AidGenRecord record = new AidGenRecord();
        record.setUserId(dto.getUserId());
        // 冗余项目与剧集维度，支持生成记录反查。
        record.setProjectId(storyboard.getProjectId());
        record.setEpisodeId(storyboard.getEpisodeId());
        record.setStoryboardId(dto.getStoryboardId());
        record.setGenType("i2v");
        record.setBaseImageId(dto.getBaseImageRecordId());
        record.setUserInputText(dto.getGenParams() != null ? dto.getGenParams().getVideoPrompt() : null);
        record.setGenParams(JSON.toJSONString(dto.getGenParams()));
        record.setRemark(payloadJson);
        record.setStatus(0); // 处理中
        record.setCreateTime(DateUtils.getNowDate());
        record.setDelFlag("0");
        aidGenRecordService.insertAidGenRecord(record);

        log.info("分镜视频生成记录已创建, recordId={}, storyboardId={}, baseImageRecordId={}, userId={}",
                record.getId(), dto.getStoryboardId(), dto.getBaseImageRecordId(), dto.getUserId());

        MediaVideoGenerateRequest videoRequest = new MediaVideoGenerateRequest();
        videoRequest.setPrompt(finalPrompt);
        videoRequest.setImageUrl(baseImageUrl);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("referenceImages", referenceImages);
        options.put("localRecordId", record.getId());
        options.put("payloadSnapshot", payloadJson);
        videoRequest.setOptions(options);

        // 视频生成未暴露模型入参时，使用 Agent 默认模型与参数。
        AgentModelDefault videoAgentModel = agentModelResolver.resolveDefault(storyboard.getProjectId(), AgentScene.STORYBOARD_VIDEO);
        if (StringUtils.isEmpty(videoRequest.getModelName())) {
            videoRequest.setModelName(videoAgentModel.getModelCode());
        }
        AiModelConfigVo videoModelConfig = aiModelConfigService.selectByModelCode(videoAgentModel.getModelCode());
        agentDefaultParamsApplier.applyToVideo(videoAgentModel, videoRequest, videoModelConfig);

        MediaTaskResponse taskResponse;
        try {
            taskResponse = mediaGenerationService.generateVideo(videoRequest);
        } catch (Exception ex) {
            // 媒体提交失败时显式标记记录失败，避免任务长期停留在处理中。
            log.error("分镜生视频提交失败, recordId={}", record.getId(), ex);
            record.setStatus(2);
            record.setUpdateTime(DateUtils.getNowDate());
            aidGenRecordService.updateById(record);
            throw ex;
        }

        log.info("大模型生视频任务已提交, recordId={}, taskId={}, status={}",
                record.getId(), taskResponse.getTaskId(), taskResponse.getStatus());

        if (taskResponse.getTaskId() != null) {
            record.setTaskId(taskResponse.getTaskId().toString());
        }
        // Provider 同步失败但未抛异常时，同步修正本地记录状态。
        if ("FAILED".equals(taskResponse.getStatus())) {
            record.setStatus(2);
        }
        record.setUpdateTime(DateUtils.getNowDate());
        aidGenRecordService.updateById(record);

        return MediaGenRespDTO.builder()
                .recordId(record.getId())
                .taskId(taskResponse.getTaskId() != null ? taskResponse.getTaskId().toString() : null)
                .status(taskResponse.getStatus())
                .build();
    }

    private String buildImageFinalPrompt(GenerationParams genParams) {
        if (genParams == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add(genParams.getImagePrompt());
        appendPromptContent(parts, genParams.getShotSize());
        appendPromptContent(parts, genParams.getCameraAngle());
        appendPromptContent(parts, genParams.getColorTone());
        appendPromptContent(parts, genParams.getFocalLength());
        appendPromptContent(parts, genParams.getLighting());
        appendPromptContent(parts, genParams.getExposureBlur());
        String finalPrompt = parts.stream().filter(StringUtils::isNotEmpty).collect(Collectors.joining(", "));
        log.debug("图片最终Prompt: {}", finalPrompt);
        return finalPrompt;
    }

    private String buildVideoFinalPrompt(GenerationParams genParams) {
        if (genParams == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add(genParams.getVideoPrompt());
        appendPromptContent(parts, genParams.getCameraMovement());
        appendPromptContent(parts, genParams.getShootingTechnique());
        String finalPrompt = parts.stream().filter(StringUtils::isNotEmpty).collect(Collectors.joining(", "));
        log.debug("视频最终Prompt: {}", finalPrompt);
        return finalPrompt;
    }

    private void appendPromptContent(List<String> parts, String promptName) {
        if (StringUtils.isEmpty(promptName)) {
            return;
        }
        LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.<AidPromptLib>lambdaQuery()
                .eq(AidPromptLib::getPromptName, promptName)
                .eq(AidPromptLib::getDelFlag, "0")
                .eq(AidPromptLib::getStatus, "0")
                .last("LIMIT 1");
        AidPromptLib promptLib = aidPromptLibService.getOne(wrapper, false);
        if (promptLib != null && StringUtils.isNotEmpty(promptLib.getPromptContent())) {
            parts.add(promptLib.getPromptContent());
        }
    }

    private List<String> fetchAllAssetUrlsFromGenParams(GenerationParams genParams) {
        if (genParams == null) {
            return Collections.emptyList();
        }
        Set<Long> allAssetIds = new LinkedHashSet<>();
        parseAndCollectIds(allAssetIds, genParams.getCharacterIds());
        parseAndCollectIds(allAssetIds, genParams.getPropIds());
        parseAndCollectIds(allAssetIds, genParams.getSceneIds());
        parseAndCollectIds(allAssetIds, genParams.getPoseIds());
        parseAndCollectIds(allAssetIds, genParams.getExpressionIds());
        parseAndCollectIds(allAssetIds, genParams.getEffectIds());
        parseAndCollectIds(allAssetIds, genParams.getSketchIds());
        if (allAssetIds.isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<AidComicAsset> wrapper = Wrappers.<AidComicAsset>lambdaQuery()
                .in(AidComicAsset::getId, allAssetIds)
                .eq(AidComicAsset::getDelFlag, "0");
        return aidComicAssetService.list(wrapper).stream()
                .map(AidComicAsset::getImageUrl)
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.toList());
    }

    private void parseAndCollectIds(Set<Long> target, String idsStr) {
        if (StringUtils.isEmpty(idsStr)) {
            return;
        }
        for (String idStr : idsStr.split(",")) {
            String trimmed = idStr.trim();
            if (StringUtils.isNotEmpty(trimmed)) {
                try {
                    target.add(Long.parseLong(trimmed));
                } catch (NumberFormatException e) {
                    log.warn("资产ID解析失败，跳过: {}", trimmed);
                }
            }
        }
    }

    private void validateImageReq(ImageGenReqDTO dto) {
        if (dto == null) { throw new ServiceException("请求参数不能为空"); }
        if (dto.getStoryboardId() == null) { throw new ServiceException("分镜不能为空"); }
        if (dto.getUserId() == null) { throw new ServiceException("参数不能为空"); }
        validateUserExists(dto.getUserId());
    }

    private void validateVideoReq(VideoGenReqDTO dto) {
        if (dto == null) { throw new ServiceException("请求参数不能为空"); }
        if (dto.getStoryboardId() == null) { throw new ServiceException("分镜不能为空"); }
        if (dto.getUserId() == null) { throw new ServiceException("参数不能为空"); }
        if (dto.getBaseImageRecordId() == null) { throw new ServiceException("请先生成分镜图片"); }
        validateUserExists(dto.getUserId());
    }

    private void validateTextReq(TextGenReqDTO dto) {
        if (dto == null) { throw new ServiceException("请求参数不能为空"); }
        if (dto.getStoryboardId() == null) { throw new ServiceException("分镜不能为空"); }
        if (dto.getUserId() == null) { throw new ServiceException("参数不能为空"); }
        if (StringUtils.isEmpty(dto.getPrompt())
                && (dto.getMessages() == null || dto.getMessages().stream().allMatch(m -> StringUtils.isEmpty(m.getContent())))) {
            throw new ServiceException("提示词不能为空");
        }
        validateUserExists(dto.getUserId());
    }

    /**
     * 校验生成参数。
     *
     * @param genParams     生成参数
     * @param isEconomyMode 是否为经济模式
     * @param isImageGen    是否为图片生成
     */
    private void validateGenParams(GenerationParams genParams, boolean isEconomyMode, boolean isImageGen) {
        if (genParams == null) {
            return;
        }

        if (isEconomyMode) {
            log.info("经济模式：跳过资产和摄影参数校验");
            return;
        }

        log.info("性能模式：开始校验所有生成参数");

        if (isImageGen && StringUtils.isEmpty(genParams.getImagePrompt())) {
            throw new ServiceException("性能模式下，画面描述不能为空");
        }
        if (!isImageGen && StringUtils.isEmpty(genParams.getVideoPrompt())) {
            throw new ServiceException("性能模式下，动作描述不能为空");
        }

        validateAssetIdsExist(genParams);

        validatePromptParamsExist(genParams);
    }

    /**
     * 校验资产 ID 是否存在。
     */
    private void validateAssetIdsExist(GenerationParams genParams) {
        Set<Long> allAssetIds = new LinkedHashSet<>();
        parseAndCollectIds(allAssetIds, genParams.getSceneIds());
        parseAndCollectIds(allAssetIds, genParams.getCharacterIds());
        parseAndCollectIds(allAssetIds, genParams.getPropIds());
        parseAndCollectIds(allAssetIds, genParams.getPoseIds());
        parseAndCollectIds(allAssetIds, genParams.getExpressionIds());
        parseAndCollectIds(allAssetIds, genParams.getEffectIds());
        parseAndCollectIds(allAssetIds, genParams.getSketchIds());

        if (allAssetIds.isEmpty()) {
            return;
        }

        LambdaQueryWrapper<AidComicAsset> wrapper = Wrappers.<AidComicAsset>lambdaQuery()
                .in(AidComicAsset::getId, allAssetIds)
                .eq(AidComicAsset::getDelFlag, "0");
        List<AidComicAsset> existAssets = aidComicAssetService.list(wrapper);
        Set<Long> existIds = existAssets.stream().map(AidComicAsset::getId).collect(Collectors.toSet());

        Set<Long> missingIds = new LinkedHashSet<>(allAssetIds);
        missingIds.removeAll(existIds);
        if (!missingIds.isEmpty()) {
            throw new ServiceException("以下资产ID不存在或已被删除: " + missingIds);
        }
    }

    /**
     * 校验摄影参数是否存在。
     */
    private void validatePromptParamsExist(GenerationParams genParams) {
        Map<String, String> fieldNameMap = new LinkedHashMap<>();
        if (StringUtils.isNotEmpty(genParams.getShotSize())) {
            fieldNameMap.put(genParams.getShotSize(), "景别");
        }
        if (StringUtils.isNotEmpty(genParams.getCameraAngle())) {
            fieldNameMap.put(genParams.getCameraAngle(), "拍摄角度");
        }
        if (StringUtils.isNotEmpty(genParams.getFocalLength())) {
            fieldNameMap.put(genParams.getFocalLength(), "焦距");
        }
        if (StringUtils.isNotEmpty(genParams.getColorTone())) {
            fieldNameMap.put(genParams.getColorTone(), "色彩色调");
        }
        if (StringUtils.isNotEmpty(genParams.getLighting())) {
            fieldNameMap.put(genParams.getLighting(), "光线");
        }
        if (StringUtils.isNotEmpty(genParams.getExposureBlur())) {
            fieldNameMap.put(genParams.getExposureBlur(), "曝光虚化");
        }
        if (StringUtils.isNotEmpty(genParams.getCameraMovement())) {
            fieldNameMap.put(genParams.getCameraMovement(), "运镜");
        }
        if (StringUtils.isNotEmpty(genParams.getShootingTechnique())) {
            fieldNameMap.put(genParams.getShootingTechnique(), "拍摄技法");
        }

        if (fieldNameMap.isEmpty()) {
            return;
        }

        Set<String> promptNames = fieldNameMap.keySet();
        LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.<AidPromptLib>lambdaQuery()
                .in(AidPromptLib::getPromptName, promptNames)
                .eq(AidPromptLib::getDelFlag, "0")
                .eq(AidPromptLib::getStatus, "0");
        List<AidPromptLib> existPrompts = aidPromptLibService.list(wrapper);
        Set<String> existNames = existPrompts.stream()
                .map(AidPromptLib::getPromptName)
                .collect(Collectors.toSet());

        List<String> invalidItems = new ArrayList<>();
        for (Map.Entry<String, String> entry : fieldNameMap.entrySet()) {
            if (!existNames.contains(entry.getKey())) {
                invalidItems.add(entry.getValue() + "「" + entry.getKey() + "」");
            }
        }
        if (!invalidItems.isEmpty()) {
            throw new ServiceException("以下摄影参数在提示词库中不存在: " + String.join(", ", invalidItems));
        }
    }

    private void validateUserExists(Long userId) {
        if (sysUserService.selectUserById(userId) == null) {
            throw new ServiceException("用户不存在, userId=" + userId);
        }
    }

    private AidStoryboard validateAndGetStoryboard(Long storyboardId, Long userId) {
        AidStoryboard storyboard = aidStoryboardService.selectAidStoryboardById(storyboardId);
        if (storyboard == null) { throw new ServiceException("分镜记录不存在, storyboardId=" + storyboardId); }
        if (!"0".equals(storyboard.getDelFlag())) { throw new ServiceException("分镜记录已被删除, storyboardId=" + storyboardId); }
        if (!userId.equals(storyboard.getUserId())) { throw new ServiceException("无权操作该分镜"); }
        return storyboard;
    }
}
