package com.aid.storyboard.service;

import java.util.List;
import com.aid.storyboard.dto.DeleteGenRecordRequest;
import com.aid.storyboard.dto.GenerateAudioRequest;
import com.aid.storyboard.dto.GenerateMediaRequest;
import com.aid.storyboard.dto.GenRecordDetailRequest;
import com.aid.storyboard.dto.GenRecordListRequest;
import com.aid.storyboard.dto.SetFinalImageRequest;
import com.aid.storyboard.dto.SetFinalSelectionRequest;
import com.aid.storyboard.dto.StoryboardCreateRequest;
import com.aid.storyboard.dto.StoryboardDeleteRequest;
import com.aid.storyboard.dto.StoryboardDetailRequest;
import com.aid.storyboard.dto.StoryboardGenRecordListRequest;
import com.aid.storyboard.dto.StoryboardListRequest;
import com.aid.storyboard.dto.StoryboardSaveRequest;
import com.aid.storyboard.dto.StoryboardSortRequest;
import com.aid.storyboard.dto.UploadStoryboardImageRequest;
import com.aid.storyboard.vo.AudioTaskVO;
import com.aid.storyboard.vo.GenRecordVO;
import com.aid.storyboard.vo.StoryboardVO;
import com.aid.common.vo.BatchOperationResultVO;

/**
 * 分镜工作台核心业务Service接口
 *
 * @author 视觉AID
 */
public interface IStoryboardWorkbenchService {

    /**
     * 查询分镜列表
     *
     * @param request 查询条件
     * @param userId 当前用户ID
     * @return 分镜列表
     */
    List<StoryboardVO> listStoryboards(StoryboardListRequest request, Long userId);

    /**
     * 查询分镜详情
     *
     * @param request 查询条件
     * @param userId 当前用户ID
     * @return 分镜详情VO
     */
    StoryboardVO getStoryboardDetail(StoryboardDetailRequest request, Long userId);

    /**
     * 新增分镜
     *
     * @param request 创建请求
     * @param userId 当前用户ID
     * @return 新增的分镜VO
     */
    StoryboardVO createStoryboard(StoryboardCreateRequest request, Long userId);

    /**
     * 删除分镜（软删除，单删 / 批删合并；任一分镜不存在/已删/不归属则整批拒绝）。
     *
     * @param request 删除请求（ids 单个即单删、多个即批删）
     * @param userId 当前用户ID
     * @return 实际软删除的分镜条数
     */
    int deleteStoryboard(StoryboardDeleteRequest request, Long userId);

    /**
     * 批量调整分镜排序
     *
     * @param request 排序请求
     * @param userId 当前用户ID
     */
    void sortStoryboards(StoryboardSortRequest request, Long userId);

    /**
     * 保存/更新分镜图纸配置
     *
     * @param request 分镜配置请求
     * @param userId 当前用户ID
     */
    void saveStoryboard(StoryboardSaveRequest request, Long userId);

    /**
     * 发起画面生成/抽卡(含路由策略)
     *
     * @param request 生成请求
     * @param userId 当前用户ID
     * @return 生成记录VO
     */
    GenRecordVO generateMedia(GenerateMediaRequest request, Long userId);

    /**
     * 发起配音任务
     *
     * @param request 配音请求
     * @param userId 当前用户ID
     * @return 配音任务VO
     */
    AudioTaskVO generateAudio(GenerateAudioRequest request, Long userId);

    /**
     * 查询音频业务记录（供前端轮询，仅读 aid_audio_record）。
     *
     * @param taskId 音频业务记录ID（aid_audio_record.id）
     * @param userId 当前用户ID，用于归属校验
     * @return 音频任务VO；记录不存在或不属于当前用户时抛出业务异常
     */
    AudioTaskVO queryAudioTask(Long taskId, Long userId);

    /**
     * 确认最终产物(排他更新，按genType区分互斥范围)
     *
     * @param request 确认请求
     * @param userId 当前用户ID
     */
    void setFinalSelection(SetFinalSelectionRequest request, Long userId);

    /**
     * 查询生成记录列表
     *
     * @param request 查询条件
     * @param userId 当前用户ID
     * @return 生成记录列表
     */
    List<GenRecordVO> listGenRecords(GenRecordListRequest request, Long userId);

    /**
     * 查询生成记录详情
     *
     * @param request 查询条件
     * @param userId 当前用户ID
     * @return 生成记录详情VO
     */
    GenRecordVO getGenRecordDetail(GenRecordDetailRequest request, Long userId);

    /**
     * 按"项目 + 剧集 + 类型"查询当前用户在该项目 / 剧集下的所有生成记录列表（image 类含 image/grid，video 类含 i2v/multi/edge）。
     *
     * @param request 入参（projectId / episodeId / type 必填）
     * @param userId  当前用户ID，用于归属校验
     * @return 列表（每条已带 {@code isSelected}）
     */
    List<GenRecordVO> listGenRecordsByStoryboard(StoryboardGenRecordListRequest request, Long userId);

    /**
     * 用户自行上传分镜媒体（图片 / 视频），关联到指定分镜并落库 {@code aid_gen_record}（默认未选中、状态成功）。
     *
     * @param request 上传请求（projectId / episodeId / storyboardId / imageUrl / mediaType）
     * @param userId  当前用户ID
     * @return 新增的生成记录 VO
     */
    GenRecordVO uploadStoryboardImage(UploadStoryboardImageRequest request, Long userId);

    /**
     * 物理删除分镜生成记录：若被删记录为分镜最终图 / 最终视频，则同步清空分镜对应的 final_image_id / final_video_id。
     *
     * @param request 删除请求（storyboardId + recordId）
     * @param userId  当前用户ID
     */
    void deleteGenRecord(DeleteGenRecordRequest request, Long userId);

    /**
     * 取消分镜最终图片选中（setFinalImage 的反向操作）；仅当当前 final_image_id 与传入 recordId 一致时清除（幂等）。
     *
     * @param request 入参（与 setFinalImage 同结构）
     * @param userId  当前用户ID
     */
    void unsetFinalImage(SetFinalImageRequest request, Long userId);

    /**
     * 取消分镜最终视频选中（setFinalVideo 的反向操作）；仅当当前 final_video_id 与传入 recordId 一致时清除（幂等）。
     *
     * @param request 入参（与 setFinalVideo 同结构：storyboardId + recordId）
     * @param userId  当前用户ID
     */
    void unsetFinalVideo(SetFinalImageRequest request, Long userId);

    /**
     * 批量设置分镜最终图片（单个 / 批量同接口，逐条独立事务，单条失败不牵连其它）。
     *
     * @param projectId 项目 ID（必填，范围闸门）
     * @param episodeId 剧集 ID（必填，电影传 0，剧集传 &gt; 0）
     * @param items     待设置条目列表（每项 storyboardId + recordId）
     * @param userId    当前用户ID
     * @return 批量操作结果
     */
    BatchOperationResultVO setFinalImageBatch(Long projectId, Long episodeId,
                                              List<SetFinalImageRequest.Item> items, Long userId);

    /**
     * 批量设置分镜最终视频（单个 / 批量同接口，逐条独立事务，单条失败不牵连其它）。
     *
     * @param projectId 项目 ID（必填，范围闸门）
     * @param episodeId 剧集 ID（必填）
     * @param items     待设置条目列表
     * @param userId    当前用户ID
     * @return 批量操作结果
     */
    BatchOperationResultVO setFinalVideoBatch(Long projectId, Long episodeId,
                                              List<SetFinalImageRequest.Item> items, Long userId);

    /**
     * 批量取消分镜最终图片（单个 / 批量同接口，逐条独立事务，单条失败不牵连其它）。
     *
     * @param projectId 项目 ID（必填，范围闸门）
     * @param episodeId 剧集 ID（必填）
     * @param items     待取消条目列表
     * @param userId    当前用户ID
     * @return 批量操作结果
     */
    BatchOperationResultVO unsetFinalImageBatch(Long projectId, Long episodeId,
                                                List<SetFinalImageRequest.Item> items, Long userId);

    /**
     * 批量取消分镜最终视频（单个 / 批量同接口，逐条独立事务，单条失败不牵连其它）。
     *
     * @param projectId 项目 ID（必填，范围闸门）
     * @param episodeId 剧集 ID（必填）
     * @param items     待取消条目列表
     * @param userId    当前用户ID
     * @return 批量操作结果
     */
    BatchOperationResultVO unsetFinalVideoBatch(Long projectId, Long episodeId,
                                                List<SetFinalImageRequest.Item> items, Long userId);
}
