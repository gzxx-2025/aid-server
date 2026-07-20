package com.aid.aid.controller;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.github.pagehelper.PageInfo;
import com.aid.aid.domain.AidComicProject;
import com.aid.audit.dto.AdminAuditActionRequest;
import com.aid.audit.dto.AdminAuditDetailRequest;
import com.aid.audit.dto.AdminAuditRecordQueryRequest;
import com.aid.audit.dto.AdminEpisodeAuditQueryRequest;
import com.aid.audit.dto.AdminProjectAuditQueryRequest;
import com.aid.audit.service.IAdminAuditBusinessService;
import com.aid.audit.vo.AuditEpisodeDetailVO;
import com.aid.audit.vo.AuditEpisodeListVO;
import com.aid.audit.vo.AuditMovieDetailVO;
import com.aid.audit.vo.AuditProjectDetailVO;
import com.aid.audit.vo.AuditRecordVO;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.SecurityUtils;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;

/**
 * 后台-作品审核Controller
 * 仅供后台管理端使用，路径前缀 /aid/audit，与 C 端 /api/user/* 接口完全隔离、不共用。
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/audit")
public class AidAuditController extends BaseController
{
    @Resource
    private IAdminAuditBusinessService adminAuditBusinessService;

    /**
     * 项目审核列表（仅剧集类项目，审核项目情况）
     * 不传 status 默认查「审核中」待办；返回数据放在 data 字段，total 为总数。
     *
     * @param request 查询条件
     * @return 剧集类项目列表（分页）
     */
    @PreAuthorize("@ss.hasPermi('aid:audit:list')")
    @PostMapping("/project/list")
    public AjaxResult projectList(@RequestBody AdminProjectAuditQueryRequest request)
    {
        startPage();
        List<AidComicProject> list = adminAuditBusinessService.selectAuditProjectList(request);
        return buildPageResult(list);
    }

    /**
     * 电影审核列表（仅电影类项目，审核封面+成品）
     * 不传 status 默认查「审核中」待办；返回数据放在 data 字段，total 为总数。
     *
     * @param request 查询条件
     * @return 电影类项目列表（分页）
     */
    @PreAuthorize("@ss.hasPermi('aid:audit:list')")
    @PostMapping("/movie/list")
    public AjaxResult movieList(@RequestBody AdminProjectAuditQueryRequest request)
    {
        startPage();
        List<AidComicProject> list = adminAuditBusinessService.selectAuditMovieList(request);
        return buildPageResult(list);
    }

    /**
     * 剧集审核列表
     * 不传 status 默认查「审核中」待办；返回数据放在 data 字段，total 为总数。
     *
     * @param request 查询条件
     * @return 剧集列表（分页）
     */
    @PreAuthorize("@ss.hasPermi('aid:audit:list')")
    @PostMapping("/episode/list")
    public AjaxResult episodeList(@RequestBody AdminEpisodeAuditQueryRequest request)
    {
        startPage();
        List<AuditEpisodeListVO> list = adminAuditBusinessService.selectAuditEpisodeList(request);
        return buildPageResult(list);
    }

    /**
     * 审核项目（通过/驳回）
     * pass=true 通过(状态→4)，pass=false 驳回(状态→5，reason 必填)。仅「审核中」可操作。
     *
     * @param request 审核操作请求
     * @return 操作结果
     */
    @PreAuthorize("@ss.hasPermi('aid:audit:audit')")
    @Log(title = "项目审核", businessType = BusinessType.UPDATE)
    @PostMapping("/project/audit")
    public AjaxResult auditProject(@Valid @RequestBody AdminAuditActionRequest request)
    {
        adminAuditBusinessService.auditProject(request, SecurityUtils.getUsername());
        return AjaxResult.success("审核成功");
    }

    /**
     * 审核剧集（通过/驳回）
     * pass=true 通过(状态→4)，pass=false 驳回(状态→5，reason 必填)。仅「审核中」可操作。
     *
     * @param request 审核操作请求
     * @return 操作结果
     */
    @PreAuthorize("@ss.hasPermi('aid:audit:audit')")
    @Log(title = "剧集审核", businessType = BusinessType.UPDATE)
    @PostMapping("/episode/audit")
    public AjaxResult auditEpisode(@Valid @RequestBody AdminAuditActionRequest request)
    {
        adminAuditBusinessService.auditEpisode(request, SecurityUtils.getUsername());
        return AjaxResult.success("审核成功");
    }

    /**
     * 项目审核详情
     * 返回项目信息 + 成品视频在线地址（finalVideoUrl）供审核员观看。
     *
     * @param request 详情请求（项目ID）
     * @return 项目审核详情
     */
    @PreAuthorize("@ss.hasPermi('aid:audit:query')")
    @PostMapping("/project/detail")
    public AjaxResult projectDetail(@Valid @RequestBody AdminAuditDetailRequest request)
    {
        AuditProjectDetailVO detail = adminAuditBusinessService.getProjectAuditDetail(request);
        return AjaxResult.success(detail);
    }

    /**
     * 电影审核详情
     * 返回电影封面 + 成品视频在线地址（finalVideoUrl）供审核员审核。
     *
     * @param request 详情请求（电影项目ID）
     * @return 电影审核详情
     */
    @PreAuthorize("@ss.hasPermi('aid:audit:query')")
    @PostMapping("/movie/detail")
    public AjaxResult movieDetail(@Valid @RequestBody AdminAuditDetailRequest request)
    {
        AuditMovieDetailVO detail = adminAuditBusinessService.getMovieAuditDetail(request);
        return AjaxResult.success(detail);
    }

    /**
     * 剧集审核详情
     * 返回剧集信息 + 成品视频在线地址（finalVideoUrl）供审核员观看。
     *
     * @param request 详情请求（剧集ID）
     * @return 剧集审核详情
     */
    @PreAuthorize("@ss.hasPermi('aid:audit:query')")
    @PostMapping("/episode/detail")
    public AjaxResult episodeDetail(@Valid @RequestBody AdminAuditDetailRequest request)
    {
        AuditEpisodeDetailVO detail = adminAuditBusinessService.getEpisodeAuditDetail(request);
        return AjaxResult.success(detail);
    }

    /**
     * 审核流水记录列表
     * 支持按对象类型、对象ID、所属用户、审核动作筛选；返回带中文描述的记录VO。
     *
     * @param request 查询条件
     * @return 审核记录列表（分页）
     */
    @PreAuthorize("@ss.hasPermi('aid:audit:list')")
    @PostMapping("/record/list")
    public AjaxResult recordList(@RequestBody AdminAuditRecordQueryRequest request)
    {
        startPage();
        List<AuditRecordVO> list = adminAuditBusinessService.selectAuditRecordList(request);
        return buildPageResult(list);
    }

    /**
     * 组装分页返回：数据统一放 data 字段，total 为总数
     *
     * @param list 数据列表
     * @return 分页结果
     */
    private AjaxResult buildPageResult(List<?> list)
    {
        AjaxResult result = AjaxResult.success();
        result.put("total", new PageInfo(list).getTotal());
        result.put("data", list);
        return result;
    }
}
