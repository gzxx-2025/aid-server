package com.aid.project.service;

import com.aid.common.core.domain.AjaxResult;
import com.aid.project.dto.PublicVideoQueryRequest;
import com.aid.project.vo.PublicProjectDetailVO;

/**
 * 公开项目业务Service接口
 *
 * @author 视觉AID
 */
public interface IPublicProjectBusinessService
{
    /**
     * 查询公开广场项目列表（电影+剧集，含作者昵称/类型/介绍/发布时间）
     *
     * @param request 查询请求（可选projectName模糊搜索、projectType类型筛选）
     * @return 分页结果（total总数 + data公开视频VO列表）
     */
    AjaxResult listPublicVideo(PublicVideoQueryRequest request);

    /**
     * 查询公开项目详情
     *
     * @param projectId 项目ID
     * @return 公开项目详情VO
     */
    PublicProjectDetailVO getPublicProjectDetail(Long projectId);
}
