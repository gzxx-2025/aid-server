package com.aid.rps.service;

import java.util.List;
import com.aid.common.vo.BatchOperationResultVO;
import com.aid.rps.dto.RpsCreateRequest;
import com.aid.rps.dto.RpsDeleteRequest;
import com.aid.rps.dto.RpsFormCreateRequest;
import com.aid.rps.dto.RpsFormListRequest;
import com.aid.rps.dto.RpsQueryRequest;
import com.aid.rps.dto.RpsUpdateFormRequest;
import com.aid.rps.dto.RpsUpdateMainRequest;
import com.aid.rps.vo.RpsAssetVO;
import com.aid.rps.vo.RpsFormVO;

/**
 * 角色道具场景资产业务Service接口
 *
 * @author 视觉AID
 */
public interface IRpsBusinessService {

    /**
     * 创建主表资产（仅角色/场景/道具）
     *
     * @param request 创建请求
     * @param userId 用户ID
     * @return 创建后的资产VO
     */
    RpsAssetVO createAsset(RpsCreateRequest request, Long userId);

    /**
     * 创建从表形态（上传/官方导入）
     *
     * @param request 创建请求
     * @param userId 用户ID
     * @return 主表完整VO（含所有从表形态）
     */
    RpsAssetVO createForm(RpsFormCreateRequest request, Long userId);

    /**
     * AI生成从表形态
     *
     * @param request 创建请求
     * @param userId 用户ID
     * @return 主表完整VO（含所有从表形态）
     */
    RpsAssetVO createAiForm(RpsFormCreateRequest request, Long userId);

    /**
     * 查询资产列表
     *
     * @param request 查询条件
     * @param userId 用户ID
     * @return 资产列表
     */
    List<RpsAssetVO> queryAssetList(RpsQueryRequest request, Long userId);

    /**
     * 仅更新主表资产名称（角色/场景/道具）
     *
     * @param request 编辑请求
     * @param userId 用户ID
     * @return 编辑后的资产VO
     */
    RpsAssetVO updateMainAsset(RpsUpdateMainRequest request, Long userId);

    /**
     * 仅更新从表形态（名称/图片/提示词）
     *
     * @param request 编辑请求
     * @param userId 用户ID
     * @return 编辑后的单个形态 VO（含 images）
     */
    RpsFormVO updateFormAsset(RpsUpdateFormRequest request, Long userId);

    /**
     * 删除资产：传 formId 仅删该形态，不传则删除主资产及其全部形态；
     * form_image 物理删除并级联清理 OSS 文件。
     *
     * @param request 删除请求
     * @param userId 用户ID
     */
    void deleteAsset(RpsDeleteRequest request, Long userId);

    /**
     * 批量删除主资产（单个 / 批量同接口，逐条独立事务，汇总成功 / 失败明细）。
     *
     * @param ids    主资产ID列表（去重有序）
     * @param userId 用户ID
     * @return 批量操作结果（成功 / 失败明细）
     */
    BatchOperationResultVO deleteAssetBatch(List<Long> ids, Long userId);

    /**
     * 设置指定图片为"使用中"（多选语义：仅设置目标图，不影响同 form 其它图片）。
     *
     * @param imageId 图片实例ID
     * @param userId  用户ID
     */
    void useForm(Long imageId, Long userId);

    /**
     * 取消指定图片的"使用中"状态（同 form 至少保留一张使用中）。
     *
     * @param imageId 图片实例ID
     * @param userId  用户ID
     */
    void unuseForm(Long imageId, Long userId);

    /**
     * 批量设置图片为"使用中"（单个 / 批量同接口，逐条独立事务，汇总成功 / 失败明细）。
     *
     * @param projectId 项目 ID（必填，范围闸门）
     * @param imageIds  图片实例ID列表（去重有序）
     * @param userId    用户ID
     * @return 批量操作结果（成功 / 失败明细）
     */
    BatchOperationResultVO useFormBatch(Long projectId, List<Long> imageIds, Long userId);

    /**
     * 批量取消图片的"使用中"状态（单个 / 批量同接口，逐条独立事务，汇总成功 / 失败明细）。
     *
     * @param projectId 项目 ID（必填，范围闸门）
     * @param imageIds  图片实例ID列表（去重有序）
     * @param userId    用户ID
     * @return 批量操作结果（成功 / 失败明细）
     */
    BatchOperationResultVO unuseFormBatch(Long projectId, List<Long> imageIds, Long userId);

    /**
     * 查询形态列表（三层模型形态层视角，批量聚合杜绝 N+1，返回每个 form 的完整图片列表）。
     *
     * @param request 查询条件
     * @param userId  用户ID
     * @return 形态列表（含每个 form 的完整 form_image 列表）
     */
    List<RpsFormVO> queryFormList(RpsFormListRequest request, Long userId);
}
