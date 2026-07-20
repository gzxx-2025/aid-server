package com.aid.rps.controller;

import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.aid.common.vo.BatchOperationResultVO;

import java.util.Objects;
import com.aid.rps.dto.RpsCreateRequest;
import com.aid.rps.dto.RpsDeleteRequest;
import com.aid.rps.dto.RpsFormCreateRequest;
import com.aid.rps.dto.RpsFormImageCreateRequest;
import com.aid.rps.dto.RpsFormImageDeleteRequest;
import com.aid.rps.dto.RpsFormImageListRequest;
import com.aid.rps.dto.RpsFormImageUpdateRequest;
import com.aid.rps.dto.RpsFormImageUpscaleRequest;
import com.aid.rps.dto.RpsFormImageUseRequest;
import com.aid.rps.dto.RpsFormListRequest;
import com.aid.rps.dto.RpsQueryRequest;
import com.aid.rps.dto.RpsSceneFormImageSplitRequest;
import com.aid.rps.dto.RpsUpdateFormRequest;
import com.aid.rps.dto.RpsUpdateMainRequest;
import com.aid.rps.service.IRpsBusinessService;
import com.aid.rps.service.IRpsFormImageBusinessService;
import com.aid.rps.vo.RpsAssetVO;
import com.aid.rps.vo.RpsFormImageDetailVO;
import com.aid.rps.vo.RpsFormVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
/**
 * 角色道具场景资产Controller
 * C端个人资产CRUD接口
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/api/user/asset/rps")
public class RpsController extends BaseController
{
    /** form_image 使用中切换批量上限（单批最多 50 条，超出整体拒收避免占用过多线程/数据库连接） */
    private static final int MAX_FORM_USE_BATCH = 50;

    /** 场景拆分四宫格批量上限（单批最多 10 张；单张涉及下载+切4图+4次OSS上传+短事务，故更激进保护） */
    private static final int MAX_SCENE_SPLIT_BATCH = 10;

    @Resource
    private IRpsBusinessService rpsBusinessService;

    /** 形态图片实例业务 Service（用于上传 / 官方导入 / 删除图片） */
    @Resource
    private IRpsFormImageBusinessService rpsFormImageBusinessService;

    /**
     * 接口1：创建主表资产（仅角色/场景/道具）
     */
    @PostMapping("/create")
    public AjaxResult create(@Valid @RequestBody RpsCreateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            RpsAssetVO vo = rpsBusinessService.createAsset(request, userId);
            return success(vo);
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 接口2：创建从表形态（手动上传/官方导入/AI生成）
     */
    @PostMapping("/form/create")
    public AjaxResult formCreate(@Valid @RequestBody RpsFormCreateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            RpsAssetVO vo;
            if (java.util.Objects.equals("ai", request.getSourceType())) {
                vo = rpsBusinessService.createAiForm(request, userId);
            } else {
                vo = rpsBusinessService.createForm(request, userId);
            }
            return success(vo);
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 接口5：查询资产列表（按项目+剧集）
     */
    @PostMapping("/list")
    public AjaxResult list(@Valid @RequestBody RpsQueryRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            List<RpsAssetVO> list = rpsBusinessService.queryAssetList(request, userId);
            return success(list);
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 接口8：仅更新主表资产名称（角色/场景/道具）
     */
    @PostMapping("/update-main")
    public AjaxResult updateMain(@Valid @RequestBody RpsUpdateMainRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            RpsAssetVO vo = rpsBusinessService.updateMainAsset(request, userId);
            return success(vo);
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 接口9：仅更新从表形态（名称/图片/提示词），返回更新后的单个 form VO（含 images）
     */
    @PostMapping("/update-form")
    public AjaxResult updateForm(@Valid @RequestBody RpsUpdateFormRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            RpsFormVO vo = rpsBusinessService.updateFormAsset(request, userId);
            return success(vo);
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 接口7：删除资产（物理删除，级联清理其下形态、图片与 OSS 文件）
     */
    @PostMapping("/delete")
    public AjaxResult delete(@Valid @RequestBody RpsDeleteRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            rpsBusinessService.deleteAsset(request, userId);
            return success("删除成功");
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 接口10：设置图片为使用中（单个 / 批量同接口）。
     * 单个：传 {@code imageId}（或兼容旧 {@code id}）；批量：传 {@code imageIds} 列表。
     * 三者解析后去重合并，逐条独立事务执行，单条失败不牵连其它，出参为统一批量结果
     * {@link BatchOperationResultVO}（含成功 / 失败明细）。
     * 批量上限 {@value #MAX_FORM_USE_BATCH} 条；超出整体拒收。
     * 入参里被静默丢弃的非法 ID（null 元素）会以 {@code failures[]} 中 id=null + reason=参数缺失 的形式透出。
     */
    @PostMapping("/form/use")
    public AjaxResult useForm(@Valid @RequestBody(required = false) RpsFormImageUseRequest request)
    {
        // 空请求体 / {} / 三字段全空统一报“参数缺失”，避免 Spring 抛 400 跳过业务响应
        if (Objects.isNull(request) || !request.hasAnyId()) {
            return error("参数缺失");
        }
        // 批量上限保护：先看原始提交数（含 null）以防绕过 effective 去重后通过
        int rawCount = request.rawIdCount();
        if (rawCount > MAX_FORM_USE_BATCH) {
            return error("批量过多");
        }
        Long userId = SecurityUtils.getUserId();
        try {
            List<Long> effective = request.effectiveImageIds();
            // 解析时被静默丢弃的非法条目按“参数缺失”透出，与前端口径对齐
            int dropped = rawCount - effective.size();
            BatchOperationResultVO result = rpsBusinessService.useFormBatch(request.getProjectId(), effective, userId);
            appendDroppedFailures(result, dropped);
            return success(result);
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 接口11：取消图片使用（单个 / 批量同接口）。
     * 入参同 {@code /form/use}；逐条独立事务取消使用中，单条失败（如同 form 需保留至少一张）
     * 不牵连其它，出参为统一批量结果。批量上限 {@value #MAX_FORM_USE_BATCH} 条。
     */
    @PostMapping("/form/unuse")
    public AjaxResult unuseForm(@Valid @RequestBody(required = false) RpsFormImageUseRequest request)
    {
        if (Objects.isNull(request) || !request.hasAnyId()) {
            return error("参数缺失");
        }
        int rawCount = request.rawIdCount();
        if (rawCount > MAX_FORM_USE_BATCH) {
            return error("批量过多");
        }
        Long userId = SecurityUtils.getUserId();
        try {
            List<Long> effective = request.effectiveImageIds();
            int dropped = rawCount - effective.size();
            BatchOperationResultVO result = rpsBusinessService.unuseFormBatch(request.getProjectId(), effective, userId);
            appendDroppedFailures(result, dropped);
            return success(result);
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 把“前端提交但解析时被静默丢弃”的非法条目以 id=null + reason=参数缺失 的形式追加到批量结果，
     * 避免前端误以为 effective 后的 total 即全部成功。
     */
    private void appendDroppedFailures(BatchOperationResultVO result, int dropped)
    {
        for (int i = 0; i < dropped; i++) {
            result.addFailure(null, "参数缺失");
        }
        result.summarize();
    }
    /**
     * 接口12：创建形态图片实例（用于上传 / 官方导入；AI 生图走 /asset/extract/form/generate-image）。
     */
    @PostMapping("/form-image/create")
    public AjaxResult formImageCreate(@Valid @RequestBody RpsFormImageCreateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            return success(rpsFormImageBusinessService.createImage(request, userId));
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 接口13：删除形态图片实例（物理删除并清理 OSS 文件；仅删图片、不删 form）。
     */
    @PostMapping("/form-image/delete")
    public AjaxResult formImageDelete(@Valid @RequestBody RpsFormImageDeleteRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            rpsFormImageBusinessService.deleteImage(request.getImageId(), userId);
            return success("删除成功");
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }
    /**
     * 接口14：查询形态列表（三层模型形态层视角）。
     * 批量查 form + form_image 后内存分组聚合，杜绝 N+1。
     * 返回每个 form 的完整 form_image 列表（不按 is_use 过滤）；如需仅看使用中，请走 /list。
     */
    @PostMapping("/form/list")
    public AjaxResult formList(@RequestBody(required = false) RpsFormListRequest request)
    {
        // 请求体可为空，null 由 Service 层统一保护
        Long userId = SecurityUtils.getUserId();
        try {
            List<RpsFormVO> list = rpsBusinessService.queryFormList(request, userId);
            return success(list);
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 接口15：编辑形态图片实例（仅改 form_image 单行）。
     * 支持改 name / imageUrl / descriptionIndex / promptSnapshot / referenceImages；
     * 不修改主资产、不修改 form。
     */
    @PostMapping("/form-image/update")
    public AjaxResult formImageUpdate(@Valid @RequestBody RpsFormImageUpdateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            RpsFormImageDetailVO vo = rpsFormImageBusinessService.updateImage(request, userId);
            return success(vo);
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 接口16：查询形态图片列表（三层模型形态图层视角）。
     * formId 非空时仅查该 form 下；formId 为空时必须传 assetType（限定范围）。
     * 单条返回带归属（formId / assetId / assetType / formName / assetName / projectId / episodeId）。
     */
    @PostMapping("/form-image/list")
    public AjaxResult formImageList(@RequestBody(required = false) RpsFormImageListRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            List<RpsFormImageDetailVO> list = rpsFormImageBusinessService.queryImageList(request, userId);
            return success(list);
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 接口18：提交图片高清任务（异步）。
     * 对齐 {@code /api/user/asset/extract/form/generate-image} 的异步任务模式：
     * 提交后立即返回 taskId + PENDING 状态，后端异步执行高清生成，
     * 成功后覆盖原 imageId 对应图片内容，前端通过现有任务系统查看进度/结果。
     */
    @PostMapping("/form-image/upscale")
    public AjaxResult formImageUpscale(@Valid @RequestBody RpsFormImageUpscaleRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            return success(rpsFormImageBusinessService.upscaleImage(request, userId));
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }

    /**
     * 接口19：场景拆分四宫格（单个 / 批量同接口；后端切图，仅场景图可拆，且产物不可再拆）。
     */
    @PostMapping("/form-image/scene/split")
    public AjaxResult sceneSplit(@Valid @RequestBody(required = false) RpsSceneFormImageSplitRequest request)
    {
        if (Objects.isNull(request) || request.effectiveSourceIds().isEmpty()) {
            return error("参数缺失");
        }
        int rawCount = request.rawIdCount();
        if (rawCount > MAX_SCENE_SPLIT_BATCH) {
            return error("批量过多");
        }
        Long userId = SecurityUtils.getUserId();
        try {
            List<Long> effective = request.effectiveSourceIds();
            int dropped = rawCount - effective.size();
            com.aid.rps.vo.RpsSceneFormImageBatchSplitVO vo =
                    rpsFormImageBusinessService.splitSceneImageBatch(request.getProjectId(), effective, userId);
            // 把被静默丢弃的非法条目以参数缺失透出
            if (dropped > 0 && Objects.nonNull(vo.getSummary())) {
                for (int i = 0; i < dropped; i++) {
                    vo.getSummary().addFailure(null, "参数缺失");
                }
                vo.getSummary().summarize();
            }
            return success(vo);
        } catch (ServiceException e) {
            logger.error("RPS接口业务拒绝: {}", e.getMessage());
            return error(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("RPS接口处理异常", e);
            return error("操作失败");
        }
    }
}
