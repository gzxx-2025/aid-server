package com.aid.storyboard.controller;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.aid.domain.dto.MediaGenRespDTO;
import com.aid.domain.dto.TextGenReqDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.common.vo.BatchOperationResultVO;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.dto.StoryboardScriptBatchRequest;
import com.aid.rps.dto.StoryboardImagePromptBatchRequest;
import com.aid.rps.dto.StoryboardImageWithPromptRequest;
import com.aid.rps.dto.StoryboardVideoWithPromptRequest;
import com.aid.rps.dto.StoryboardImageReferenceResolveRequest;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.resolver.StoryboardImageReferenceResolver;
import com.aid.rps.service.IStoryboardScriptService;
import com.aid.rps.service.IStoryboardImagePromptService;
import com.aid.storyboard.dto.AudioTaskQueryRequest;
import com.aid.storyboard.dto.GenerateAudioRequest;
import com.aid.storyboard.dto.GenerateMediaRequest;
import com.aid.storyboard.dto.LipSyncRequest;
import com.aid.storyboard.dto.StoryboardAudioBatchRequest;
import com.aid.storyboard.dto.StoryboardLipSyncBatchRequest;
import com.aid.storyboard.dto.SetFinalImageRequest;
import com.aid.storyboard.dto.SetFinalSelectionRequest;
import com.aid.storyboard.dto.StoryboardEditImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardEditImageGenerateVO;
import com.aid.storyboard.dto.StoryboardImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardImageGenerateVO;
import com.aid.storyboard.dto.StoryboardImageUpscaleRequest;
import com.aid.storyboard.dto.StoryboardMultiViewGridImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardMultiViewGridImageGenerateVO;
import com.aid.storyboard.dto.UploadStoryboardImageRequest;
import com.aid.storyboard.service.IStoryboardAudioBatchService;
import com.aid.storyboard.service.IStoryboardEditImageService;
import com.aid.storyboard.service.IStoryboardImageGenerationService;
import com.aid.storyboard.service.IStoryboardImageUpscaleService;
import com.aid.storyboard.service.IStoryboardLipSyncService;
import com.aid.storyboard.service.IStoryboardMultiViewGridImageService;
import com.aid.storyboard.service.IStoryboardWorkbenchService;
import com.aid.storyboard.vo.AudioTaskVO;
import com.aid.storyboard.vo.GenRecordVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜生成Controller
 * 提供给C端用户使用的图片/视频/音频生成及产物确认接口
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/storyboard")
public class StoryboardGenerationController extends BaseController {

    /** 分镜最终图 / 最终视频 批量上限：单批最多 50 条，超出整体拒收 */
    private static final int MAX_FINAL_BATCH = 50;

    @Resource
    private IStoryboardWorkbenchService storyboardWorkbenchService;

    /** 批量配音服务（父任务编排，逐条复用单分镜配音链路）。 */
    @Resource
    private IStoryboardAudioBatchService storyboardAudioBatchService;

    /** 对口型服务（单个 + 批量：台词现场 TTS 配音后与分镜视频一并提交对口型模型）。 */
    @Resource
    private IStoryboardLipSyncService storyboardLipSyncService;

    @Resource
    private IStoryboardScriptService storyboardScriptService;

    @Resource
    private IStoryboardImagePromptService storyboardImagePromptService;

    /** 分镜图生成服务。 */
    @Resource
    private IStoryboardImageGenerationService storyboardImageGenerationService;

    /** 分镜编辑图服务。 */
    @Resource
    private IStoryboardEditImageService storyboardEditImageService;

    /** 分镜图高清服务。 */
    @Resource
    private IStoryboardImageUpscaleService storyboardImageUpscaleService;

    /** 分镜机位生图服务。 */
    @Resource
    private IStoryboardMultiViewGridImageService storyboardMultiViewGridImageService;

    /** 分镜图生视频服务。 */
    @Resource
    private com.aid.storyboard.service.IStoryboardVideoGenerationService storyboardVideoGenerationService;

    /** 分镜图脚本占位解析器：把 image_prompt 中的 @图片N[name] 解析成 form_image 的 ID 与 URL。 */
    @Resource
    private StoryboardImageReferenceResolver storyboardImageReferenceResolver;

    @Resource
    private IWechatNotifyService wechatNotifyService;


    /**
     * 发起画面生成/抽卡
     */
    @PostMapping("/generate/media")
    public AjaxResult generateMedia(@Valid @RequestBody GenerateMediaRequest request) {
        Long userId = SecurityUtils.getUserId();
        GenRecordVO vo = storyboardWorkbenchService.generateMedia(request, userId);
        return success(vo);
    }

    /**
     * 发起配音任务。
     * 服务端会先对 ttsText 做台词标记清洗（剥掉 [角色_形象]：/ @音频N[...] / 竖线分隔符等结构标记，
     * 仅保留可朗读正文），落库与按字符计费均以清洗后的文本为准；清洗后为空返回"配音文字无效"。
     */
    @PostMapping("/generate/audio")
    public AjaxResult generateAudio(@Valid @RequestBody GenerateAudioRequest request) {
        Long userId = SecurityUtils.getUserId();
        AudioTaskVO vo = storyboardWorkbenchService.generateAudio(request, userId);
        return success(vo);
    }

    /**
     * 查询音频任务（供前端轮询 PROCESSING → SUCCEEDED / FAILED）。
     * 只读 aid_audio_record，不穿透 aid_media_task；成功时 audioUrl 为系统可长期访问的 OSS/CDN URL，
     * 不会返回上游临时签名地址。
     */
    @GetMapping("/audio/{taskId}")
    public AjaxResult getAudioTask(@PathVariable("taskId") Long taskId) {
        Long userId = SecurityUtils.getUserId();
        AudioTaskVO vo = storyboardWorkbenchService.queryAudioTask(taskId, userId);
        return success(vo);
    }

    /**
     * 查询音频任务（POST 版，与 GET /audio/{taskId} 等价；C 端统一 POST 规范入口，GET 版保留兼容）。
     * 只读 aid_audio_record；成功时 audioUrl 为系统可长期访问的 OSS/CDN URL。
     */
    @PostMapping("/audio/detail")
    public AjaxResult getAudioTaskDetail(@Valid @RequestBody AudioTaskQueryRequest request) {
        Long userId = SecurityUtils.getUserId();
        AudioTaskVO vo = storyboardWorkbenchService.queryAudioTask(request.getTaskId(), userId);
        return success(vo);
    }

    /**
     * 批量配音（受理型异步，产物=每分镜一条「配音合成视频」生成记录并自动设为使用中）。
     * 按项目+剧集为「有台词」的分镜逐条执行：① 按台词角色的绑定音色 TTS（剧集精确绑定优先，
     * 兜底用请求音色；有台词分镜双空整批拒绝"请先绑定音色"）；② 把配音贴回该分镜最终视频，
     * 合成一条新的配音视频（落 aid_gen_record，genType=compose，配音按视频时长截齐、无黑尾）；
     * ③ 新配音视频自动设为该分镜"使用中"（最终视频），原视频自动置为未使用（记录保留不覆盖）。
     * 无台词的分镜自动跳过；overwrite=true 重配同样只新增记录、绝不覆盖原始视频。
     * 配音与合成均复用统一媒体任务与统一计费。返回父任务 taskId，前端订阅任务 SSE 获取进度与
     * 逐分镜结果（含配音视频记录ID + 原视频记录ID，无需轮询）；任务终态自动推微信模板消息。
     */
    @PostMapping("/generate/audio/batch")
    public AjaxResult batchGenerateAudio(@Valid @RequestBody StoryboardAudioBatchRequest request) {
        Long userId = SecurityUtils.getUserId();
        AssetExtractTaskVO vo = storyboardAudioBatchService.batchGenerateAudio(request, userId);
        wechatNotifyService.notifyTaskStarted(vo.getTaskId());
        return success(vo);
    }

    /**
     * 确认最终产物(排他更新)
     */
    @PostMapping("/setFinal")
    public AjaxResult setFinalSelection(@Valid @RequestBody SetFinalSelectionRequest request) {
        Long userId = SecurityUtils.getUserId();
        storyboardWorkbenchService.setFinalSelection(request, userId);
        return success("确认成功");
    }

    /**
     * 设置分镜最终图片（单个 / 批量同接口）。
     */
    @PostMapping("/setFinalImage")
    public AjaxResult setFinalImage(@RequestBody(required = false) SetFinalImageRequest request) {
        AjaxResult invalid = preCheckBatch(request, true);
        if (Objects.nonNull(invalid)) {
            return invalid;
        }
        Long userId = SecurityUtils.getUserId();
        List<SetFinalImageRequest.Item> effective = request.effectiveItems();
        int dropped = request.rawItemCount() - effective.size();
        BatchOperationResultVO result = storyboardWorkbenchService.setFinalImageBatch(
                request.getProjectId(), request.getEpisodeId(), effective, userId);
        appendDroppedFailures(result, dropped);
        return success(result);
    }

    /**
     * 取消分镜最终图片（{@link #setFinalImage} 反向操作；单个 / 批量同接口）。
     * 入参同 {@code /setFinalImage}；幂等：当前 {@code final_image_id} 不匹配的条目视为已取消，仍计成功。
     * 批量上限 {@value #MAX_FINAL_BATCH} 条；取消接口本身幂等，不强制同分镜唯一。
     */
    @PostMapping("/unSetFinalImage")
    public AjaxResult unsetFinalImage(@RequestBody(required = false) SetFinalImageRequest request) {
        AjaxResult invalid = preCheckBatch(request, false);
        if (Objects.nonNull(invalid)) {
            return invalid;
        }
        Long userId = SecurityUtils.getUserId();
        List<SetFinalImageRequest.Item> effective = request.effectiveItems();
        int dropped = request.rawItemCount() - effective.size();
        BatchOperationResultVO result = storyboardWorkbenchService.unsetFinalImageBatch(
                request.getProjectId(), request.getEpisodeId(), effective, userId);
        appendDroppedFailures(result, dropped);
        return success(result);
    }

    /**
     * 设置分镜最终视频（单个 / 批量同接口，与 {@link #setFinalImage} 对称）。
     * 批量上限 {@value #MAX_FINAL_BATCH} 条；同一批次内禁止同分镜重复条目，重复整体拒收报"重复条目"。
     */
    @PostMapping("/setFinalVideo")
    public AjaxResult setFinalVideo(@RequestBody(required = false) SetFinalImageRequest request) {
        AjaxResult invalid = preCheckBatch(request, true);
        if (Objects.nonNull(invalid)) {
            return invalid;
        }
        Long userId = SecurityUtils.getUserId();
        List<SetFinalImageRequest.Item> effective = request.effectiveItems();
        int dropped = request.rawItemCount() - effective.size();
        BatchOperationResultVO result = storyboardWorkbenchService.setFinalVideoBatch(
                request.getProjectId(), request.getEpisodeId(), effective, userId);
        appendDroppedFailures(result, dropped);
        return success(result);
    }

    /**
     * 取消分镜最终视频（{@link #setFinalVideo} 反向操作；单个 / 批量同接口）。
     * 入参同 {@code /setFinalVideo}；幂等：当前 {@code final_video_id} 不匹配的条目视为已取消，仍计成功。
     * 批量上限 {@value #MAX_FINAL_BATCH} 条；取消接口本身幂等，不强制同分镜唯一。
     */
    @PostMapping("/unSetFinalVideo")
    public AjaxResult unsetFinalVideo(@RequestBody(required = false) SetFinalImageRequest request) {
        AjaxResult invalid = preCheckBatch(request, false);
        if (Objects.nonNull(invalid)) {
            return invalid;
        }
        Long userId = SecurityUtils.getUserId();
        List<SetFinalImageRequest.Item> effective = request.effectiveItems();
        int dropped = request.rawItemCount() - effective.size();
        BatchOperationResultVO result = storyboardWorkbenchService.unsetFinalVideoBatch(
                request.getProjectId(), request.getEpisodeId(), effective, userId);
        appendDroppedFailures(result, dropped);
        return success(result);
    }

    /**
     * 批量入参前置校验：空体、全空条目、项目/剧集缺失或超出批量上限时直接拒绝。
     *
     * @param request        入参（允许 null）
     * @param checkDuplicate true=设置类需校验同 storyboardId 唯一；false=取消类幂等不强制
     * @return 校验失败的 AjaxResult；通过返回 null
     */
    private AjaxResult preCheckBatch(SetFinalImageRequest request, boolean checkDuplicate) {
        if (Objects.isNull(request) || request.effectiveItems().isEmpty()) {
            return error("参数缺失");
        }
        // 项目 / 剧集字段必填（与 @NotNull 重复校验，避免 @Valid 关闭时也能兜底）
        if (Objects.isNull(request.getProjectId()) || Objects.isNull(request.getEpisodeId())) {
            return error("参数缺失");
        }
        if (request.rawItemCount() > MAX_FINAL_BATCH) {
            return error("批量过多");
        }
        if (checkDuplicate) {
            // 同分镜在一次批量里只允许一对最终产物（防"后写覆盖前写"但都报成功的歧义）
            Set<Long> seen = new HashSet<>();
            for (SetFinalImageRequest.Item it : request.effectiveItems()) {
                if (!seen.add(it.getStoryboardId())) {
                    return error("重复条目");
                }
            }
        }
        return null;
    }

    /**
     * 把"前端提交但解析时被静默丢弃"的非法条目以 id=null + reason=参数缺失 的形式追加到批量结果，
     * 避免前端误以为 effective 后的 total 即全部成功。
     */
    private void appendDroppedFailures(BatchOperationResultVO result, int dropped) {
        for (int i = 0; i < dropped; i++) {
            result.addFailure(null, "参数缺失");
        }
        result.summarize();
    }

    /**
     * 发起对口型合成（异步受理）。
     * 取分镜台词（清洗结构标记后的可朗读正文）现场 TTS 生成配音（角色绑定音色优先、请求兜底音色其次），
     * 再把「分镜视频（final_video_id 原视频）+ 本次配音」提交对口型视频大模型（音频驱动），
     * 全程走统一媒体任务系统与统一计费（TTS 按字符、对口型按秒预冻结，失败自动退款）。
     * 受理成功返回 lipSyncStatus=PROCESSING 的配音任务VO；前端轮询 GET /audio/{taskId}，
     * lipSyncStatus=SUCCEEDED 时 syncVideoUrl 即对口型视频 URL，FAILED 表示合成失败（已退款，可重新发起）。
     * 产物同时落配音视频记录（genType=compose，不自动选中，用户可手动设为配音视频主视频）。
     */
    @PostMapping("/lipSync")
    public AjaxResult lipSync(@Valid @RequestBody LipSyncRequest request) {
        Long userId = SecurityUtils.getUserId();
        AudioTaskVO vo = storyboardLipSyncService.lipSync(request, userId);
        return success(vo);
    }

    /**
     * 批量对口型（受理型异步，产物=每分镜一条「对口型配音视频」生成记录并自动设为配音视频主视频）。
     * 按项目+剧集为「有台词」的分镜逐条执行：① 台词现场 TTS 配音（角色绑定音色优先，兜底用请求音色；
     * 有台词分镜双空整批拒绝"请先绑定音色"）；② 把「分镜视频 + 本次配音」提交对口型模型；
     * ③ 产物（genType=compose）自动设为该分镜配音视频主视频（compose 类内排他，分镜视频不受影响）。
     * 无台词的分镜自动跳过；overwrite=true 重做同样只新增记录、绝不覆盖。
     * 配音与对口型均复用统一媒体任务与统一计费。返回父任务 taskId，前端订阅任务 SSE 获取进度与
     * 逐分镜结果（含对口型视频记录ID + 源视频记录ID）；任务终态自动推微信模板消息。
     */
    @PostMapping("/lipSync/batch")
    public AjaxResult batchLipSync(@Valid @RequestBody StoryboardLipSyncBatchRequest request) {
        Long userId = SecurityUtils.getUserId();
        AssetExtractTaskVO vo = storyboardLipSyncService.batchLipSync(request, userId);
        wechatNotifyService.notifyTaskStarted(vo.getTaskId());
        return success(vo);
    }

    /**
     * 批量生成分镜脚本
     * 按项目+剧集维度，遍历所有已提取场景，逐场景调用分镜脚本 LLM 智能体，
     * 将输出写入 aid_storyboard 表。返回父任务 taskId 供 SSE 追踪进度。
     */
    @PostMapping("/generate/script")
    public AjaxResult batchGenerateScript(@Valid @RequestBody StoryboardScriptBatchRequest request) {
        Long userId = SecurityUtils.getUserId();
        AssetExtractTaskVO vo = storyboardScriptService.batchGenerateStoryboardScript(
                request.getProjectId(),
                request.getEpisodeId(),
                userId,
                request.getSceneIds(),
                request.getAgentCode(),
                request.getModelCode(),
                request.getMode(),
                request.getOverwrite());
        wechatNotifyService.notifyTaskStarted(vo.getTaskId());
        return success(vo);
    }

    /**
     * 批量生成分镜图脚本（图生图 prompt）。
     * 把上一步 {@link #batchGenerateScript} 写入 {@code aid_storyboard.script_params} 的镜头数据，
     * 逐条调用"分镜画师"智能体（biz_category_code=main_storyboard_stylist）生成图生图 prompt，
     * 写回 {@code aid_storyboard.image_prompt} / {@code image_prompt_raw}。
     * 返回父任务 taskId 供前端 SSE 订阅进度。
     */
    @PostMapping("/generate/image-prompt")
    public AjaxResult batchGenerateImagePrompt(@Valid @RequestBody StoryboardImagePromptBatchRequest request) {
        Long userId = SecurityUtils.getUserId();
        AssetExtractTaskVO vo = storyboardImagePromptService.batchGenerateImagePrompt(
                request.getProjectId(),
                request.getEpisodeId(),
                userId,
                request.getStoryboardIds(),
                request.getAgentCode(),
                request.getModelCode(),
                request.getOverwrite(),
                null);
        wechatNotifyService.notifyTaskStarted(vo.getTaskId());
        return success(vo);
    }

    /**
     * 批量生成分镜图提示词 + 自动出图（合并接口，一次点击两步）。
     * 先提交「分镜图提示词」批量任务；其终态（成功/部分成功）后由后端
     * {@code StoryboardStepChainService} 对已成功产出提示词的分镜自动发起「批量出图」。
     * 出图模型/比例等可选覆盖项放入 {@code genXxx}，为空则按项目/智能体默认解析。
     * 返回的是提示词父任务 taskId（前端先订阅出词进度，出图任务完成后通过分镜产物刷新）。
     */
    @PostMapping("/generate/image-with-prompt")
    public AjaxResult batchGenerateImageWithPrompt(@Valid @RequestBody StoryboardImageWithPromptRequest request) {
        Long userId = SecurityUtils.getUserId();
        java.util.Map<String, Object> chainNext = new java.util.LinkedHashMap<>();
        chainNext.put("type", "image");
        chainNext.put("agentCode", request.getGenAgentCode());
        chainNext.put("modelName", request.getGenModelName());
        chainNext.put("aspectRatio", request.getGenAspectRatio());
        chainNext.put("size", request.getGenSize());
        chainNext.put("scenario", request.getGenScenario());
        chainNext.put("negativePrompt", request.getGenNegativePrompt());
        AssetExtractTaskVO vo = storyboardImagePromptService.batchGenerateImagePrompt(
                request.getProjectId(),
                request.getEpisodeId(),
                userId,
                request.getStoryboardIds(),
                request.getAgentCode(),
                request.getModelCode(),
                request.getOverwrite(),
                chainNext);
        wechatNotifyService.notifyTaskStarted(vo.getTaskId());
        return success(vo);
    }
    @Resource
    private com.aid.rps.service.IStoryboardVideoPromptService storyboardVideoPromptService;
    /**
     * 批量生成分镜视频提示词（视觉导演融合版）。
     * 把 {@code aid_storyboard.image_prompt}（图生图 prompt）与 {@code script_params}
     * 中的镜头运动/台词/动作状态合成为视频生成提示词，写回 {@code aid_storyboard.video_prompt}。
     * 返回父任务 taskId 供前端 SSE 订阅进度。
     */
    @PostMapping("/generate/video-prompt")
    public AjaxResult batchGenerateVideoPrompt(@Valid @RequestBody com.aid.rps.dto.StoryboardVideoPromptBatchRequest request) {
        Long userId = SecurityUtils.getUserId();
        AssetExtractTaskVO vo = storyboardVideoPromptService.batchGenerateVideoPrompt(
                request.getProjectId(),
                request.getEpisodeId(),
                userId,
                request.getStoryboardIds(),
                request.getAgentCode(),
                request.getModelCode(),
                request.getOverwrite(),
                null);
        wechatNotifyService.notifyTaskStarted(vo.getTaskId());
        return success(vo);
    }

    /**
     * 批量生成分镜视频提示词 + 自动出片（合并接口，按创作模式自动路由，一次点击两步）。
     */
    @PostMapping("/generate/video-with-prompt")
    public AjaxResult batchGenerateVideoWithPrompt(@Valid @RequestBody StoryboardVideoWithPromptRequest request) {
        Long userId = SecurityUtils.getUserId();
        AssetExtractTaskVO vo = storyboardVideoPromptService.batchGenerateVideoWithPromptAuto(request, userId);
        wechatNotifyService.notifyTaskStarted(vo.getTaskId());
        return success(vo);
    }

    /**
     * 批量生成图生方向（漫剧版）分镜视频提示词。
     * 把 {@code aid_storyboard.image_prompt}（分镜画面生图提示词）+ 分镜脚本（画面/台词/动作/镜头运动）
     * + 本剧集角色形态设定 + 全局风格，经"视觉导演（漫剧版）"智能体
     * （biz_category_code=main_storyboard_video_prompt_image、默认 agent=aid_visual_director_image）
     * 整段批量合成为图生视频提示词，写回 {@code aid_storyboard.video_prompt_image}（与多参方向物理隔离）。
     * 复用多参方向同一套任务/计费/锁/队列；返回父任务 taskId 供前端 SSE 订阅进度。
     */
    @PostMapping("/generate/video-prompt-image")
    public AjaxResult batchGenerateVideoPromptImage(@Valid @RequestBody com.aid.rps.dto.StoryboardVideoPromptImageBatchRequest request) {
        Long userId = SecurityUtils.getUserId();
        AssetExtractTaskVO vo = storyboardVideoPromptService.batchGenerateVideoPromptImage(
                request.getProjectId(),
                request.getEpisodeId(),
                userId,
                request.getStoryboardIds(),
                request.getAgentCode(),
                request.getModelCode(),
                request.getOverwrite());
        wechatNotifyService.notifyTaskStarted(vo.getTaskId());
        return success(vo);
    }

    /**
     * 批量生成宫格方向（auto_grid 专业版）分镜视频提示词。
     * 把专业版「中文镜头组」脚本 + 宫格图提示词（{@code aid_storyboard.image_prompt}）+ 全局风格，经"视觉导演（宫格版）"
     * 智能体（biz_category_code=main_storyboard_video_prompt_grid、默认 agent=aid_visual_director_grid）整段批量合成为
     * 宫格图生视频提示词，复用回填 {@code aid_storyboard.video_prompt_image}（宫格属图生方向出片，不新增列）。
     * 仅 auto_grid 创作模式使用；复用同一套任务/计费/锁/队列，返回父任务 taskId 供前端 SSE 订阅进度。
     */
    @PostMapping("/generate/video-prompt-grid")
    public AjaxResult batchGenerateVideoPromptGrid(@Valid @RequestBody com.aid.rps.dto.StoryboardVideoPromptImageBatchRequest request) {
        Long userId = SecurityUtils.getUserId();
        AssetExtractTaskVO vo = storyboardVideoPromptService.batchGenerateVideoPromptGrid(
                request.getProjectId(),
                request.getEpisodeId(),
                userId,
                request.getStoryboardIds(),
                request.getAgentCode(),
                request.getModelCode(),
                request.getOverwrite());
        wechatNotifyService.notifyTaskStarted(vo.getTaskId());
        return success(vo);
    }

    /**
     * 手动保存分镜视频提示词。
     */
    @PostMapping("/save/video-prompt")
    public AjaxResult saveManualVideoPrompt(@Valid @RequestBody com.aid.rps.dto.StoryboardVideoPromptManualRequest request) {
        Long userId = SecurityUtils.getUserId();
        storyboardVideoPromptService.saveManualVideoPrompt(request.getStoryboardId(), request.getVideoPrompt(), userId);
        return success("保存成功");
    }

    /**
     * 解析分镜图脚本中的 @图片N[name] 占位为 form_image 的 ID + URL + 名称明细。
     *
     * @param request 待解析的 image_prompt + 项目/剧集归属
     * @return data: {referenceImageIds, referenceImageUrls, references, unresolvedNames}
     */
    @PostMapping("/image-prompt/resolve")
    public AjaxResult resolveImagePromptReferences(
            @Valid @RequestBody StoryboardImageReferenceResolveRequest request) {
        Long userId = SecurityUtils.getUserId();
        StoryboardImageReferenceResolver.ResolveResult vo =
                storyboardImageReferenceResolver.resolve(
                        request.getImagePrompt(),
                        request.getProjectId(),
                        userId);
        return success(vo);
    }

    /**
     * 生成分镜图。
     *
     * @param request 入参（storyboardIds 必填，其余可选）
     * @return data: {@link StoryboardImageGenerateVO}
     */
    @PostMapping("/generate/image")
    public AjaxResult generateStoryboardImage(@Valid @RequestBody StoryboardImageGenerateRequest request) {
        Long userId = SecurityUtils.getUserId();
        try
        {
            StoryboardImageGenerateVO vo = storyboardImageGenerationService.generateImage(request, userId);
            wechatNotifyService.notifyTaskStarted(vo.getTaskId());
            return AjaxResult.success("提交成功", vo);
        }
        catch (RuntimeException e)
        {
            logger.error(e.getMessage());
            return error(e.getMessage());
        }
    }

    /**
     * 分镜编辑图生成。
     *
     * @param request 入参（storyboardId / referenceImage / prompt / modelCode / aspectRatio / size / imageCount）
     * @return data: {@link StoryboardEditImageGenerateVO}
     */
    @PostMapping("/generate/edit-image")
    public AjaxResult generateStoryboardEditImage(@Valid @RequestBody StoryboardEditImageGenerateRequest request) {
        Long userId = SecurityUtils.getUserId();
        try
        {
            StoryboardEditImageGenerateVO vo = storyboardEditImageService.generateEditImage(request, userId);
            return success(vo);
        }
        catch (RuntimeException e)
        {
            logger.error(e.getMessage());
            return error(e.getMessage());
        }
    }

    /**
     * 分镜图高清。
     *
     * @param request 入参（genRecordId / modelCode / 可选 resolution）
     * @return data: {@link com.aid.rps.dto.AssetExtractTaskVO}（taskId + PENDING）
     */
    @PostMapping("/generate/upscale")
    public AjaxResult upscaleStoryboardImage(@Valid @RequestBody StoryboardImageUpscaleRequest request) {
        Long userId = SecurityUtils.getUserId();
        try
        {
            com.aid.rps.dto.AssetExtractTaskVO vo = storyboardImageUpscaleService.upscaleStoryboardImage(request, userId);
            return success(vo);
        }
        catch (RuntimeException e)
        {
            logger.error(e.getMessage());
            return error(e.getMessage());
        }
    }

    /**
     * 分镜机位生图 —— 单机位 / 九宫格统一入口。
     *
     * @param request 入参（storyboardId / imageUrl / angles / modelCode / aspectRatio?）
     * @return data: {@link StoryboardMultiViewGridImageGenerateVO}（taskId + PENDING）
     */
    @PostMapping("/generate/multi-view-grid-image")
    public AjaxResult generateStoryboardMultiViewGridImage(
            @Valid @RequestBody StoryboardMultiViewGridImageGenerateRequest request) {
        Long userId = SecurityUtils.getUserId();
        try
        {
            StoryboardMultiViewGridImageGenerateVO vo =
                    storyboardMultiViewGridImageService.generateMultiViewGridImage(request, userId);
            wechatNotifyService.notifyTaskStarted(vo.getTaskId());
            return success(vo);
        }
        catch (RuntimeException e)
        {
            logger.error(e.getMessage());
            return error(e.getMessage());
        }
    }

    /**
     * 用户自行上传分镜媒体（图片 / 视频）。
     *
     * @param request 入参（projectId / episodeId / storyboardId / imageUrl / mediaType）
     * @return data: {@link GenRecordVO} 新增记录（含拼好域名的 fileUrl）
     */
    @PostMapping("/upload")
    public AjaxResult uploadStoryboardImage(@Valid @RequestBody UploadStoryboardImageRequest request) {
        Long userId = SecurityUtils.getUserId();
        GenRecordVO vo = storyboardWorkbenchService.uploadStoryboardImage(request, userId);
        return success(vo);
    }

    /**
     * 分镜图生视频。
     *
     * @param request 入参（storyboardIds 必填，其余可选；单/多镜头共用，count 规则见 DTO）
     * @return data: {@link com.aid.storyboard.dto.StoryboardVideoGenerateVO}
     */
    @PostMapping("/generate/video")
    public AjaxResult generateStoryboardVideo(
            @Valid @RequestBody com.aid.storyboard.dto.StoryboardVideoGenerateRequest request) {
        Long userId = SecurityUtils.getUserId();
        try
        {
            com.aid.storyboard.dto.StoryboardVideoGenerateVO vo =
                    storyboardVideoGenerationService.generateVideo(request, userId);
            wechatNotifyService.notifyTaskStarted(vo.getTaskId());
            return success(vo);
        }
        catch (RuntimeException e)
        {
            logger.error(e.getMessage());
            return error(e.getMessage());
        }
    }

    /**
     * 分镜图生视频（图生方向，漫剧版）。
     * 仅支持一张参考图：前端经 {@code images} 直传则取第 1 张（多传忽略其余）；不传则回落分镜主图
     * {@code aid_storyboard.final_image_id} 对应图；两者皆无则报错「请选参考图」（请上传/选择一张参考图，或先设置分镜主图）。
     * 提示词来源：入参 {@code videoPrompt} 优先 → 库回落 {@code aid_storyboard.video_prompt_image}（非 video_prompt）
     * → 双空报错「请先生成提示词」；前端传了提示词则建任务前先落库该列。计费 / 排队 / 退款 / OSS / 任务体系与多参方向出片
     * 完全一致（taskType=storyboard_video_generate，SSE / 取消接口复用）。
     */
    @PostMapping("/generate/video/image")
    public AjaxResult generateStoryboardVideoFromImage(
            @Valid @RequestBody com.aid.storyboard.dto.StoryboardVideoFromImageGenerateRequest request) {
        Long userId = SecurityUtils.getUserId();
        try
        {
            com.aid.storyboard.dto.StoryboardVideoGenerateVO vo =
                    storyboardVideoGenerationService.generateVideoFromImage(request, userId);
            wechatNotifyService.notifyTaskStarted(vo.getTaskId());
            return success(vo);
        }
        catch (RuntimeException e)
        {
            logger.error(e.getMessage());
            return error(e.getMessage());
        }
    }

    /**
     * 分镜宫格生视频（宫格方向，auto_grid 专业版）。
     * 以分镜宫格图为参考、宫格视频提示词（库回落 {@code aid_storyboard.video_prompt_image}）出片，模型走宫格专属池
     * {@code main_storyboard_video_grid}。仅 auto_grid 创作模式可用，其余模式拒绝「仅宫格模式可用」。
     * 单/多镜头共用本接口（count 规则见 DTO）；计费 / 排队 / 退款 / OSS / 任务体系与其它出片方向一致
     * （taskType=storyboard_video_generate，续生走 /api/user/task/resume）。
     */
    @PostMapping("/generate/video/grid")
    public AjaxResult generateStoryboardVideoFromGrid(
            @Valid @RequestBody com.aid.storyboard.dto.StoryboardVideoGridGenerateRequest request) {
        Long userId = SecurityUtils.getUserId();
        try
        {
            com.aid.storyboard.dto.StoryboardVideoGenerateVO vo =
                    storyboardVideoGenerationService.generateVideoFromGrid(request, userId);
            wechatNotifyService.notifyTaskStarted(vo.getTaskId());
            return success(vo);
        }
        catch (RuntimeException e)
        {
            logger.error(e.getMessage());
            return error(e.getMessage());
        }
    }

    /**
     * 分镜首尾帧生视频（首尾帧方向）。
     * 以每个分镜的首图 + 尾图出片，模型走首尾帧专属池 {@code main_storyboard_video_edge}（要求模型支持尾帧输入），
     * 不按创作模式分流。首尾图经入参 {@code items} 逐镜头指定（首图记录 ID + 尾图记录 ID）；缺任一该镜头报
     * 「请选择首尾帧」。提示词单镜头入参优先 → 库回落 {@code aid_storyboard.video_prompt}。结果落
     * {@code aid_gen_record(gen_type=edge)}；计费 / 排队 / 退款 / OSS / 任务体系与其它出片方向一致
     * （taskType=storyboard_video_generate，续生走 /api/user/task/resume）。
     */
    @PostMapping("/generate/video/edge")
    public AjaxResult generateStoryboardVideoFromEdge(
            @Valid @RequestBody com.aid.storyboard.dto.StoryboardVideoEdgeGenerateRequest request) {
        Long userId = SecurityUtils.getUserId();
        try
        {
            com.aid.storyboard.dto.StoryboardVideoGenerateVO vo =
                    storyboardVideoGenerationService.generateVideoFromEdge(request, userId);
            wechatNotifyService.notifyTaskStarted(vo.getTaskId());
            return success(vo);
        }
        catch (RuntimeException e)
        {
            logger.error(e.getMessage());
            return error(e.getMessage());
        }
    }
}
