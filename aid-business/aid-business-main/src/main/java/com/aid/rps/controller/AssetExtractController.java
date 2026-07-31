package com.aid.rps.controller;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.dto.AssetExtractRequest;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.ExtractCostEstimateRequest;
import com.aid.rps.dto.FormGenerateRequest;
import com.aid.rps.dto.FormCardImageGenerateRequest;
import com.aid.rps.dto.FormEditChatImageGenerateRequest;
import com.aid.rps.dto.FormImageGenerateRequest;
import com.aid.rps.dto.FormMultiViewImageGenerateRequest;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.service.IFormEditChatImageService;
import com.aid.rps.service.IFormMultiViewImageService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * AI资产提取Controller
 * C端接口：提交批量提取任务 / 提交单资产形态生成任务。
 * 所有任务均为异步，立即返回taskId。
 * 任务列表查询、状态查询、SSE进度推送见 UserTaskController。
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/api/user/asset/extract")
public class AssetExtractController extends BaseController
{
    @Resource
    private IAssetExtractService assetExtractService;

    /** 多机位形态生图 Service（独立服务，避免 AssetExtractServiceImpl 继续膨胀） */
    @Resource
    private IFormMultiViewImageService formMultiViewImageService;

    /** 编辑弹窗生图 / 对话作图 Service（独立服务） */
    @Resource
    private IFormEditChatImageService formEditChatImageService;

    @Resource
    private IWechatNotifyService wechatNotifyService;

    /**
     * 费用预估（同步）
     * 计算剧本字数、分组数、已有角色数等信息，供前端展示确认
     */
    @PostMapping("/estimate")
    public AjaxResult estimate(@Valid @RequestBody ExtractCostEstimateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try
        {
            // 复用 AssetExtractRequest 结构
            AssetExtractRequest extractRequest = new AssetExtractRequest();
            extractRequest.setProjectId(request.getProjectId());
            extractRequest.setEpisodeId(request.getEpisodeId());
            extractRequest.setExtractTypes(request.getExtractTypes());
            extractRequest.setExtractScope(request.getExtractScope());
            return success(assetExtractService.estimateCost(extractRequest, userId));
        }
        catch (ServiceException e)
        {
            logger.error("资产提取业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        }
        catch (RuntimeException e)
        {
            logger.error("资产提取接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 提交批量AI提取任务（异步）
     * 立即返回taskId，前端通过SSE或轮询获取结果
     */
    @PostMapping("/parallel")
    public AjaxResult parallelExtract(@Valid @RequestBody AssetExtractRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try
        {
            AssetExtractTaskVO vo = assetExtractService.extractAssets(request, userId);
            wechatNotifyService.notifyTaskStarted(vo.getTaskId());
            return success(vo);
        }
        catch (ServiceException e)
        {
            logger.error("资产提取业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        }
        catch (RuntimeException e)
        {
            logger.error("资产提取接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 批量资产形态生成（父任务模式，agentCode 必填）
     * 一次请求传多个 assetIds，后端只创建 1 条父任务（form_generate_batch），
     * Consumer 内部逐项处理。返回单个 taskId + PENDING 状态。
     * 取消可用 /cancel 接口（单 taskId 取消）。
     */
    @PostMapping("/form/generate")
    public AjaxResult generateForm(@Valid @RequestBody FormGenerateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try
        {
            AssetExtractTaskVO vo = assetExtractService.batchGenerateForm(
                    request.getAssetIds(), userId, request.getAgentCode(), request.getModelCode());
            wechatNotifyService.notifyTaskStarted(vo.getTaskId());
            return success(vo);
        }
        catch (ServiceException e)
        {
            logger.error("资产提取业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        }
        catch (RuntimeException e)
        {
            logger.error("资产提取接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 批量形态图生成（父任务模式，agentCode 必填）
     * 一次请求传多个 formIds，后端只创建 1 条父任务（form_image_batch），
     * Consumer 内部逐项处理。模型编码统一作用于每个 form。
     * 形态图为基础设定图，禁止外部参考图输入。
     * 返回单个 taskId + PENDING 状态。取消可用 /cancel 接口。
     */
    @PostMapping("/form/generate-image")
    public AjaxResult generateFormImage(@Valid @RequestBody FormImageGenerateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try
        {
            AssetExtractTaskVO vo = assetExtractService.batchGenerateFormImage(
                    request.getFormIds(), userId,
                    request.getAgentCode(), request.getModelCode(),
                    request.getResolution(), request.getAspectRatio());
            wechatNotifyService.notifyTaskStarted(vo.getTaskId());
            return success(vo);
        }
        catch (ServiceException e)
        {
            logger.error("资产提取业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        }
        catch (RuntimeException e)
        {
            logger.error("资产提取接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 提交批量角色设定卡生成任务（异步，第二阶段；agentCode 必填）
     * 一次请求传多个 imageIds（白底主图实例ID），后端只创建 1 条父任务（form_card_image_batch），
     * Consumer 内部逐张处理：以白底图作为参考图，调用智能体模板生成角色设定卡，
     * 结果写入 aid_role_prop_scene_form_image（sourceType=ai_builder）。
     * 仅支持 character 类型资产；agentCode 的 biz_category_code 必须为 main_character_card_image。
     * 返回单个 taskId + PENDING 状态。取消可用 /cancel 接口。
     */
    @PostMapping("/form/generate-card-image")
    public AjaxResult generateCardImage(@Valid @RequestBody FormCardImageGenerateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try
        {
            AssetExtractTaskVO vo = assetExtractService.batchGenerateCardImage(
                    request.getImageIds(), userId,
                    request.getAgentCode(), request.getModelCode(),
                    request.getResolution(), request.getAspectRatio());
            wechatNotifyService.notifyTaskStarted(vo.getTaskId());
            return success(vo);
        }
        catch (ServiceException e)
        {
            logger.error("资产提取业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        }
        catch (RuntimeException e)
        {
            logger.error("资产提取接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 多机位形态生图（异步）。
     */
    @PostMapping("/form/generate-multi-view-image")
    public AjaxResult generateMultiViewImage(@Valid @RequestBody FormMultiViewImageGenerateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try
        {
            return success(formMultiViewImageService.generateMultiViewImage(request, userId));
        }
        catch (ServiceException e)
        {
            logger.error("资产提取业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        }
        catch (RuntimeException e)
        {
            logger.error("资产提取接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 形态图片创作（编辑图片 / 对话作图，异步）。
     */
    @PostMapping("/form/generate-creation-image")
    public AjaxResult generateEditChatImage(@Valid @RequestBody FormEditChatImageGenerateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try
        {
            return success(formEditChatImageService.generateEditChatImage(request, userId));
        }
        catch (ServiceException e)
        {
            logger.error("资产提取业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        }
        catch (RuntimeException e)
        {
            logger.error("资产提取接口处理异常", e);
            return error("操作失败");
        }
    }
}
