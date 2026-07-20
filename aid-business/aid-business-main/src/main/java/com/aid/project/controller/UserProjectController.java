package com.aid.project.controller;

import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.aid.domain.AidComicProject;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.github.pagehelper.PageInfo;
import com.aid.project.dto.UserProjectCreateRequest;
import com.aid.project.dto.UserProjectDeleteRequest;
import com.aid.project.dto.UserProjectDetailRequest;
import com.aid.project.dto.UserProjectPublishRequest;
import com.aid.project.dto.UserProjectQueryRequest;
import com.aid.project.dto.UserProjectSubmitAuditRequest;
import com.aid.project.dto.UserProjectUnpublishRequest;
import com.aid.project.dto.UserProjectUpdateRequest;
import com.aid.project.service.IUserProjectBusinessService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户项目Controller
 * 提供给C端用户使用的项目CRUD接口
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/project")
public class UserProjectController extends BaseController
{
    @Resource
    private IUserProjectBusinessService userProjectBusinessService;

    /**
     * 查询用户的项目列表。
     * 出参在项目字段基础上，为电影模式项目附加项目级成片信息
     * （episodeEditorId / finalVideoUrl / exportStatus），供前端在列表直接展示成片与「已合成」角标；
     * 为剧集类型项目附加集数 episodeCount，前端无需再逐项目调用剧集列表接口统计集数。
     *
     * @param request 查询条件
     * @return 项目列表
     */
    @PostMapping("/list")
    public AjaxResult list(@RequestBody UserProjectQueryRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        // 分页由 Service 紧邻列表查询开启（钳制 pageSize 上限），此处不再 startPage
        List<AidComicProject> list = userProjectBusinessService.selectUserProjectList(request, userId);
        // 先取分页 total（PageHelper 的 Page 对象），再转 VO 附加成片信息
        long total = new PageInfo<>(list).getTotal();
        AjaxResult result = AjaxResult.success();
        result.put("total", total);
        result.put("data", userProjectBusinessService.convertToVOList(list));
        return result;
    }

    /**
     * 获取项目详情。
     * 电影模式项目出参附加项目级成片信息（episodeEditorId / finalVideoUrl / exportStatus）。
     *
     * @param request 项目详情请求
     * @return 项目详情
     */
    @PostMapping("/detail")
    public AjaxResult getInfo(@Valid @RequestBody UserProjectDetailRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        AidComicProject project = userProjectBusinessService.selectUserProjectById(request.getId(), userId);
        if (project == null) {
            return error("项目不存在或无权限访问");
        }
        return success(userProjectBusinessService.convertToVO(project));
    }

    /**
     * 创建项目
     *
     * @param request 创建请求
     * @return 创建结果
     */
    @PostMapping("/create")
    public AjaxResult add(@Valid @RequestBody UserProjectCreateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        AidComicProject project = userProjectBusinessService.insertUserProject(request, userId);
        return success(userProjectBusinessService.convertToVO(project));
    }

    /**
     * 修改项目
     * 展示信息（项目名称 projectName / 项目介绍 projectDesc / 封面 coverUrl）随时可改，公开期间同样生效；
     * 内容参数字段（画面比例、剧本类型、视频风格、生成模式、创作模式）在公开期间锁定
     * （提示：请先关闭项目公开），须先调用 /unpublish 关闭公开后才能修改。
     *
     * @param request 修改请求
     * @return 修改结果
     */
    @PostMapping("/update")
    public AjaxResult edit(@Valid @RequestBody UserProjectUpdateRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            AidComicProject project = userProjectBusinessService.updateUserProject(request, userId);
            return success(userProjectBusinessService.convertToVO(project));
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 删除项目
     * 级联删除项目及其全部子数据（剧集、剧本、分镜、成片等）并清理对应OSS文件，删除后不可恢复。
     *
     * @param request 删除请求
     * @return 删除结果
     */
    @PostMapping("/delete")
    public AjaxResult remove(@Valid @RequestBody UserProjectDeleteRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            int result = userProjectBusinessService.softDeleteUserProjectById(request.getId(), userId);
            return toAjax(result);
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 提交项目审核
     * 除「审核中」外的状态均可提交（「审核通过」的项目仅在重新导出产生待审新片时可再次提审），
     * 提交后项目状态变为「审核中」。
     *
     * @param request 提交审核请求（项目ID）
     * @return 提交审核成功提示
     */
    @PostMapping("/submit-audit")
    public AjaxResult submitAudit(@Valid @RequestBody UserProjectSubmitAuditRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            userProjectBusinessService.submitAudit(request.getId(), userId);
            return success("提交审核成功");
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 公开项目
     * 前提：项目必须为「审核通过(4)」状态；公开后 is_public 置为 1。
     * 公开期间项目内容锁定：修改项目信息、剧集增删改、时间轴保存均被拒绝，须先关闭公开。
     * 导出成片不受锁限制：公开期间可直接重新导出，新成片进入待审槽（pendingVideoUrl），
     * 旧成片继续对外展示，重新过审后新片自动转正。
     *
     * @param request 公开请求（项目ID）
     * @return 公开后的项目详情
     */
    @PostMapping("/publish")
    public AjaxResult publish(@Valid @RequestBody UserProjectPublishRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            AidComicProject project = userProjectBusinessService.publishProject(request.getId(), userId);
            return success(userProjectBusinessService.convertToVO(project));
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 关闭项目公开（下架）
     * is_public 置回 0，项目从公开列表下架，内容恢复可修改；审核状态（status）保持不变，
     * 可直接再次公开；重新导出的新成片进入待审槽，重新过审后自动转正。
     * 未公开时幂等返回当前项目。
     *
     * @param request 关闭公开请求（项目ID）
     * @return 关闭公开后的项目详情
     */
    @PostMapping("/unpublish")
    public AjaxResult unpublish(@Valid @RequestBody UserProjectUnpublishRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try {
            AidComicProject project = userProjectBusinessService.unpublishProject(request.getId(), userId);
            return success(userProjectBusinessService.convertToVO(project));
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }
}
