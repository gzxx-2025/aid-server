package com.aid.rps.service;

import java.util.List;
import java.util.Map;
import com.aid.rps.dto.AssetExtractRequest;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.CancelBatchResult;
import com.aid.rps.vo.RpsAssetVO;

/**
 * AI资产提取Service接口
 *
 * @author 视觉AID
 */
public interface IAssetExtractService
{
    /**
     * 提交AI提取任务（异步）。
     *
     * @param request 提取请求
     * @param userId  当前用户ID
     * @return 提取任务VO（含taskId和PENDING状态）
     */
    AssetExtractTaskVO extractAssets(AssetExtractRequest request, Long userId);


    /**
     * 核心提取逻辑（由本地线程池和MQ consumer共同调用）。
     *
     * @param taskId    任务ID（用于SSE推送）
     * @param projectId 项目ID
     * @param episodeId 剧集ID
     * @param userId    用户ID
     * @return 提取的资产列表
     */
    List<RpsAssetVO> doExtract(Long taskId, Long projectId, Long episodeId, Long userId);

    /**
     * 批量资产形态生成（父任务模式，一次提交多个 assetId，只建一条父任务）。
     *
     * @param assetIds  资产ID列表
     * @param userId    当前用户ID
     * @param agentCode 智能体编码（仅 character 必填）
     * @param modelCode 可选，用户指定的文本模型；为空走智能体默认配置
     * @return 单个父任务VO（含 taskId 和 PENDING 状态）
     */
    AssetExtractTaskVO batchGenerateForm(List<Long> assetIds, Long userId, String agentCode, String modelCode);

    /**
     * 费用预估（同步）。
     *
     * @param request 预估请求
     * @param userId  当前用户ID
     * @return 预估信息（字数、组数、已有角色数等）
     */
    Map<String, Object> estimateCost(AssetExtractRequest request, Long userId);

    /**
     * 批量形态图生成（父任务模式，纯文字生图，不接受参考图）。
     *
     * @param formIds   从表形态ID列表
     * @param userId    当前用户ID
     * @param agentCode 智能体编码（必填）
     * @param modelCode   可选，用户指定的图片模型；为空走 3 级兜底
     * @param resolution  可选，清晰度档位（如 1K / 2K / 4K）；为空走 3 级兜底
     * @param aspectRatio 可选，图片比例（如 1:1 / 16:9）；为空走 3 级兜底
     * @return 单个父任务VO（含 taskId 和 PENDING 状态）
     */
    AssetExtractTaskVO batchGenerateFormImage(List<Long> formIds, Long userId, String agentCode,
                                              String modelCode, String resolution, String aspectRatio);

    /**
     * 批量形态生成核心逻辑（由 Consumer 调用），逐项处理并返回结果 JSON。
     */
    String doFormGenerateBatch(Long taskId, Long userId);

    /**
     * 批量形态图生成核心逻辑（由 Consumer 调用），逐项处理并返回结果 JSON。
     */
    String doFormImageBatch(Long taskId, Long userId);

    /**
     * 释放批量形态生成父任务的所有项目锁（供 Consumer / cancel 调用）
     */
    void releaseBatchFormLocks(Long taskId, String taskType);

    /**
     * 批量角色设定卡生成（父任务模式；白底图作参考图生成设定卡，第二阶段）。
     *
     * @param imageIds    白底图实例ID列表（aid_role_prop_scene_form_image.id）
     * @param userId      当前用户ID
     * @param agentCode   智能体编码（必填，biz_category_code=main_character_card_image）
     * @param modelCode   可选，用户指定的图片模型；为空走 3 级兜底
     * @param resolution  可选，清晰度档位；为空走 3 级兜底
     * @param aspectRatio 可选，图片比例（设定卡默认 21:9）；为空走 3 级兜底
     * @return 单个父任务VO（含 taskId 和 PENDING 状态）
     */
    AssetExtractTaskVO batchGenerateCardImage(List<Long> imageIds, Long userId, String agentCode,
                                              String modelCode, String resolution, String aspectRatio);

    /**
     * 批量角色设定卡生成核心逻辑（由 Consumer 调用），逐张处理并返回结果 JSON。
     */
    String doFormCardImageBatch(Long taskId, Long userId);

    /**
     * 素材批量任务继续生成：支持 form_generate_batch / form_image_batch / form_card_image_batch，
     * 已成功子项不重复生成，仅补跑未完成子项。
     *
     * @param taskId 父任务ID
     * @param userId 当前用户ID
     * @return 续生提交后的任务VO
     */
    AssetExtractTaskVO resumeFormBatchTask(Long taskId, Long userId);

    /**
     * 取消单个任务（统一停止接口）。
     *
     * @param taskId 任务ID
     * @param userId 当前用户ID
     */
    void cancelTask(Long taskId, Long userId);

    /**
     * 批量取消任务：只处理 PENDING / QUEUED 状态，CAS 更新为 CANCELLED。
     * CANCELLED 表示用户主动停止，可通过续生入口继续生成。
     *
     * @param taskIds 任务ID列表
     * @param userId  当前用户ID
     * @return 已受理停止/暂停的数量
     */
    CancelBatchResult cancelBatchTasks(java.util.List<Long> taskIds, Long userId);

    /**
     * 检查任务是否已被用户停止（循环检查点使用）
     *
     * @param taskId 任务ID
     * @return true=已停止
     */
    boolean isTaskCancelled(Long taskId);

    /**
     * 设置任务取消标志（供外部调用，如 cancelTask 内部已使用）
     */
    void setCancelFlag(Long taskId);

    /**
     * 释放提取防重锁（供 Consumer 调用）
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID
     */
    void releaseExtractLock(Long projectId, Long episodeId);

    /**
     * 清除 Redis 取消标记（供 Consumer 调用）
     *
     * @param taskId 任务ID
     */
    void clearCancelFlag(Long taskId);

    /**
     * 扫描提取/形态/图片任务的僵尸态并自愈（定时兜底）。
     *
     * @param staleMinutes 最低判定时长（分钟）；实际阈值取 max(staleMinutes, 类型阈值)，≤0 时仅按类型阈值
     * @param batchSize    单次扫描批量上限
     * @return 成功自愈的任务数
     */
    int reclaimZombieExtractTasks(int staleMinutes, int batchSize);

    /**
     * 资产提取续跑：从失败 chunk 接着跑，已入库资产保留不动。
     *
     * @param taskId 原父任务 ID（必须为 PARTIAL_FAILED 终态、24 小时内）
     * @param userId 当前用户 ID
     * @return 续跑提交后的任务 VO
     */
    AssetExtractTaskVO resumeExtract(Long taskId, Long userId);
    /**
     * 释放任务的多维并发名额 + 执行租约（终态 / 取消 / 僵尸回收统一调用，幂等）。
     *
     * @param taskId 任务ID
     */
    void releaseTaskSlots(Long taskId);

    /**
     * 标记任务进入执行态（PROCESSING）：登记租约心跳。
     *
     * @param taskId 任务ID
     */
    void markTaskProcessing(Long taskId);

    /**
     * 标记非阻塞扇入型任务进入执行态：仅续租一次、不登记心跳常驻集合。
     *
     * @param taskId 任务ID
     */
    void touchTaskProcessing(Long taskId);

    /**
     * 扇入型任务「同步提交阶段」结束：停止心跳续租但保留租约，转异步后由 media 轮询续租接管。
     * 与 {@link #markTaskProcessing(Long)} 配对：同步提交期间登记心跳防租约过期误杀，提交结束后调用本方法移出心跳集合。
     *
     * @param taskId 任务ID
     */
    void deactivateTaskProcessingHeartbeat(Long taskId);

    /**
     * 查询任务当前排队位次（1-based）。
     *
     * @param taskId 任务ID
     * @return 位次；不在排队中返回 null
     */
    Integer getTaskQueuePosition(Long taskId);

    /**
     * 重启即时重置：扫描 PROCESSING 但执行租约已失效的任务并立即重置。
     *
     * @param batchSize        单次扫描上限
     * @param clearLeasesFirst 是否在扫描前清空所有执行租约（单实例部署=true；多实例部署=false 仅按租约失效判定）
     * @return 实际重置的任务数
     */
    int resetLeaselessProcessingTasks(int batchSize, boolean clearLeasesFirst);

    /**
     * 重启即刻回收启动前遗留的排队/待执行任务（PENDING/QUEUED）。
     *
     * @param batchSize 单次扫描上限
     * @return 实际回收数量
     */
    int resetStartupPendingQueuedTasks(int batchSize);
}
