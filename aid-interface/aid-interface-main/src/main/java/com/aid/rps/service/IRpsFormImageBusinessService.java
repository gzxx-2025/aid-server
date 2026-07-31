package com.aid.rps.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.RpsFormImageCreateRequest;
import com.aid.rps.dto.RpsFormImageListRequest;
import com.aid.rps.dto.RpsFormImageUpdateRequest;
import com.aid.rps.dto.RpsFormImageUpscaleRequest;
import com.aid.rps.dto.RpsSceneFormImageSplitRequest;
import com.aid.rps.vo.RpsAssetVO;
import com.aid.rps.vo.RpsFormImageDetailVO;
import com.aid.rps.vo.RpsSceneFormImageBatchSplitVO;
import com.aid.rps.vo.RpsSceneFormImageSplitVO;

/**
 * 形态图片实例业务 Service（aid_role_prop_scene_form_image）：只负责图片实例增删改查。
 *
 * @author 视觉AID
 */
public interface IRpsFormImageBusinessService
{
    /**
     * 新增形态图片实例（仅 upload / official 来源；ai_auto / ai_manual 走 AssetExtract 链路）。
     *
     * @param request 创建请求
     * @param userId  用户ID
     * @return 最简主资产 VO（仅含 id / assetType / assetName）+ 本次新建图片主键 imgId
     */
    RpsAssetVO createImage(RpsFormImageCreateRequest request, Long userId);

    /**
     * 删除形态图片实例（物理删除并清理 OSS 文件；仅删图片，不删 form）。
     *
     * @param imageId 图片实例ID
     * @param userId  用户ID
     */
    void deleteImage(Long imageId, Long userId);

    /**
     * 引用即启用 + 缺失收集（生图/生视频前同步调用，原子）：可用未启用的图自动启用，存在真实缺失则不启用任何图并返回缺失列表。
     * 可引用域=项目+用户（不按集过滤），与出图解析器口径一致。
     *
     * @param projectId 项目 ID（防越权）
     * @param userId    当前用户 ID（防越权）
     * @param names     占位引用的 form_image.name 集合
     * @return 真实缺失的 name 列表（空=全部已可用或已自动启用）
     */
    List<String> enableReferencesAndCollectMissing(Long projectId, Long userId, Collection<String> names);

    /**
     * 编辑形态图片实例。
     *
     * @param request 编辑请求
     * @param userId  用户ID
     * @return 编辑后的图片详情 VO
     */
    RpsFormImageDetailVO updateImage(RpsFormImageUpdateRequest request, Long userId);

    /**
     * 查询形态图片列表（三层模型形态图层视角）。
     *
     * @param request 查询条件
     * @param userId  用户ID
     * @return 图片实例详情列表
     */
    List<RpsFormImageDetailVO> queryImageList(RpsFormImageListRequest request, Long userId);

    /**
     * 提交图片高清任务（异步）。
     *
     * @param request 高清生成请求
     * @param userId  用户ID
     * @return 任务提交结果（含 taskId 和 PENDING 状态）
     */
    AssetExtractTaskVO upscaleImage(RpsFormImageUpscaleRequest request, Long userId);

    /**
     * 执行高清生成（MQ Consumer 调用，不要直接在 Controller 中调用）。
     *
     * @param taskId 任务ID
     * @param userId 用户ID
     * @return 结果 Map（imageId / imageUrl / modelCode / resolution），由 Consumer 写入 result_data
     */
    Map<String, Object> doUpscaleImage(Long taskId, Long userId);

    /**
     * 场景拆分四宫格：把一张 scene 类型的形态图由后端切成 4 张子图入库。
     *
     * @param request 拆分请求（仅 sourceImageId）
     * @param userId  当前用户 ID
     * @return 拆分结果 VO（含源图 ID + 4 张子图详情列表，顺序：左上 / 右上 / 左下 / 右下）
     */
    RpsSceneFormImageSplitVO splitSceneImage(RpsSceneFormImageSplitRequest request, Long userId);

    /**
     * 场景批量拆分四宫格（单个 / 批量同接口）。
     *
     * @param projectId      项目 ID（必填，范围闸门）
     * @param sourceImageIds 待拆分源图 ID 去重有序列表（由 controller 解析后传入）
     * @param userId         当前用户 ID
     * @return 批量拆分结果（含汇总 + 每张成功源图的子图详情）
     */
    RpsSceneFormImageBatchSplitVO splitSceneImageBatch(Long projectId, List<Long> sourceImageIds, Long userId);
}
