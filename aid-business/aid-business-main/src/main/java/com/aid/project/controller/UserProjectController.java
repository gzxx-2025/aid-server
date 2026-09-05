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
import com.aid.project.dto.UserProjectQueryRequest;
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

}
