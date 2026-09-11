package com.aid.episode.service;

import java.util.List;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.episode.dto.UserEpisodeCreateRequest;
import com.aid.episode.dto.UserEpisodeQueryRequest;
import com.aid.episode.dto.UserEpisodeUpdateRequest;
import com.aid.episode.vo.UserEpisodeVO;

/**
 * 用户剧集业务Service接口
 *
 * @author 视觉AID
 */
public interface IUserEpisodeBusinessService
{
    /**
     * 查询用户的剧集列表
     *
     * @param request 查询条件
     * @param userId 用户ID
     * @return 剧集列表
     */
    List<AidComicEpisode> selectUserEpisodeList(UserEpisodeQueryRequest request, Long userId);

    /**
     * 查询用户的剧集详情
     *
     * @param id 剧集ID
     * @param userId 用户ID
     * @return 剧集详情
     */
    AidComicEpisode selectUserEpisodeById(Long id, Long userId);

    /**
     * 用户创建剧集
     *
     * @param request 创建请求
     * @param userId 用户ID
     * @return 新增的剧集
     */
    AidComicEpisode insertUserEpisode(UserEpisodeCreateRequest request, Long userId);

    /**
     * 用户修改剧集
     *
     * @param request 修改请求
     * @param userId 用户ID
     * @return 修改后的剧集
     */
    AidComicEpisode updateUserEpisode(UserEpisodeUpdateRequest request, Long userId);

    /**
     * 用户删除剧集（带归属校验，级联硬删除该集分镜/成片记录并清理OSS文件）
     *
     * @param id 剧集ID
     * @param userId 用户ID
     * @return 影响行数
     */
    int softDeleteUserEpisodeById(Long id, Long userId);

    /**
     * 用户提交剧集审核（带归属校验）
     * 除「审核中(3)」外的状态均可提交（「审核通过(4)」仅在存在待审新片时可重新提审，需已有成品视频），
     * 提交后状态置为「审核中(3)」并清空状态原因，同时写入审核流水。
     *
     * @param id 剧集ID
     * @param userId 用户ID
     * @return 提交审核后的剧集
     */
    AidComicEpisode submitAudit(Long id, Long userId);

    /**
     * 剧集实体批量转 VO：带出所属项目的画面比例/剧本类型/风格字段，
     * 并附加每集最新成片信息（episodeEditorId / finalVideoUrl / exportStatus，取自 aid_episode_editor）。
     *
     * @param episodes 剧集实体列表（同一用户）
     * @return VO 列表（顺序与入参一致）
     */
    List<UserEpisodeVO> convertToVOList(List<AidComicEpisode> episodes);

    /**
     * 单条剧集实体转 VO（复用批量口径）。
     *
     * @param episode 剧集实体
     * @return 剧集 VO
     */
    UserEpisodeVO convertToVO(AidComicEpisode episode);
}
