package com.aid.project.controller;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.annotation.Anonymous;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.project.dto.PublicVideoQueryRequest;
import com.aid.project.dto.UserProjectDetailRequest;
import com.aid.project.service.IPublicProjectBusinessService;

/**
 * 公开项目Controller
 * 提供无需鉴权的公开项目视频和详情查询接口
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/public/project")
public class PublicProjectController extends BaseController
{
    @Resource
    private IPublicProjectBusinessService publicProjectBusinessService;

    /**
     * 查询公开广场项目列表（电影+剧集，含作者昵称/类型/介绍/发布时间与剧集集数）
     *
     * @param request 查询请求（可选projectName模糊搜索、projectType类型筛选）
     * @return 公开视频列表（total总数 + data列表）
     */
    @Anonymous
    @PostMapping("/video")
    public AjaxResult listPublicVideo(@RequestBody PublicVideoQueryRequest request)
    {
        return publicProjectBusinessService.listPublicVideo(request);
    }

    /**
     * 查询公开项目详情
     *
     * @param request 项目详情请求（id）
     * @return 公开项目详情
     */
    @Anonymous
    @PostMapping("/detail")
    public AjaxResult getPublicProjectDetail(@Valid @RequestBody UserProjectDetailRequest request)
    {
        return success(publicProjectBusinessService.getPublicProjectDetail(request.getId()));
    }
}
