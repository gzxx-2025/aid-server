package com.aid.audit.service;

import java.util.List;
import com.aid.aid.domain.AidComicProject;
import com.aid.audit.dto.AdminAuditActionRequest;
import com.aid.audit.dto.AdminAuditDetailRequest;
import com.aid.audit.dto.AdminAuditRecordQueryRequest;
import com.aid.audit.dto.AdminEpisodeAuditQueryRequest;
import com.aid.audit.dto.AdminProjectAuditQueryRequest;
import com.aid.audit.vo.AuditEpisodeDetailVO;
import com.aid.audit.vo.AuditEpisodeListVO;
import com.aid.audit.vo.AuditMovieDetailVO;
import com.aid.audit.vo.AuditProjectDetailVO;
import com.aid.audit.vo.AuditRecordVO;

/**
 * 后台作品审核业务Service接口
 * 仅供后台管理端使用，与 C 端接口完全隔离。
 *
 * @author 视觉AID
 */
public interface IAdminAuditBusinessService {

    /**
     * 查询项目审核列表（后台）——仅剧集类项目（series），审核项目情况。
     *
     * @param request 查询条件（状态不传默认查「审核中」）
     * @return 剧集类项目列表
     */
    List<AidComicProject> selectAuditProjectList(AdminProjectAuditQueryRequest request);

    /**
     * 查询电影审核列表（后台）——仅电影类项目（movie），审核封面+成品。
     *
     * @param request 查询条件（状态不传默认查「审核中」）
     * @return 电影类项目列表
     */
    List<AidComicProject> selectAuditMovieList(AdminProjectAuditQueryRequest request);

    /**
     * 查询剧集审核列表（后台）
     *
     * @param request 查询条件（状态不传默认查「审核中」）
     * @return 剧集列表（含所属项目类型：电影/剧集）
     */
    List<AuditEpisodeListVO> selectAuditEpisodeList(AdminEpisodeAuditQueryRequest request);

    /**
     * 审核项目（通过/驳回）
     * 仅「审核中(3)」状态可审核；通过→4，驳回→5（原因必填，写入状态原因）。同时写入审核流水。
     *
     * @param request  审核操作请求
     * @param operator 审核管理员账号
     */
    void auditProject(AdminAuditActionRequest request, String operator);

    /**
     * 审核剧集（通过/驳回）
     * 仅「审核中(3)」状态可审核；通过→4，驳回→5（原因必填，写入状态原因）。同时写入审核流水。
     *
     * @param request  审核操作请求
     * @param operator 审核管理员账号
     */
    void auditEpisode(AdminAuditActionRequest request, String operator);

    /**
     * 查询审核流水记录列表（后台）
     *
     * @param request 查询条件
     * @return 审核记录VO列表
     */
    List<AuditRecordVO> selectAuditRecordList(AdminAuditRecordQueryRequest request);

    /**
     * 查询项目审核详情（后台）：只审核项目封面与基本信息，不涉及成片视频。
     *
     * @param request 详情请求（项目ID）
     * @return 项目审核详情VO
     */
    AuditProjectDetailVO getProjectAuditDetail(AdminAuditDetailRequest request);

    /**
     * 查询电影审核详情（后台）：审核封面 + 成品视频（episode_id=0）。
     *
     * @param request 详情请求（电影项目ID）
     * @return 电影审核详情VO
     */
    AuditMovieDetailVO getMovieAuditDetail(AdminAuditDetailRequest request);

    /**
     * 查询剧集审核详情（后台，含成品视频在线地址，供审核员观看）
     *
     * @param request 详情请求（剧集ID）
     * @return 剧集审核详情VO
     */
    AuditEpisodeDetailVO getEpisodeAuditDetail(AdminAuditDetailRequest request);
}
