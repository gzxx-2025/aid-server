package com.aid.notice.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.common.annotation.Anonymous;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.notice.dto.NoticeDetailRequest;
import com.aid.notice.dto.NoticeListRequest;
import com.aid.notice.service.INoticeService;
import com.aid.notice.vo.NoticeDetailVO;
import com.aid.notice.vo.NoticeListItemVO;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 公告 Controller（C 端只读）
 *
 * 本模块仅提供查询能力，不对外提供新增/修改/删除接口；
 * 公告由后台管理员在 {@code aid_notice} 中维护。接口匿名可访问，便于未登录用户浏览公告。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/notice")
public class NoticeController extends BaseController
{
    @Resource
    private INoticeService noticeService;

    /**
     * 分页查询公告列表
     * 返回当前「已显示」且处于「生效时间区间」内的公告，置顶优先、sortOrder 升序、id 倒序；
     * 列表不含完整内容，仅含标题/描述/图片/是否视频等摘要。前端根据 isVideo 决定渲染图片还是视频封面。
     *
     * @param request 查询请求（pageNum/pageSize/noticeType/keyword 均可选）
     * @return 公告分页结果（data 为列表，附 total/pageNum/pageSize）
     */
    @Anonymous
    @PostMapping("/list")
    public AjaxResult list(@RequestBody(required = false) NoticeListRequest request)
    {
        IPage<NoticeListItemVO> page = noticeService.listNotices(request);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("data", page.getRecords());
        ajax.put("total", page.getTotal());
        ajax.put("pageNum", page.getCurrent());
        ajax.put("pageSize", page.getSize());
        return ajax;
    }

    /**
     * 查询公告详情
     * 按 id 返回公告完整内容（富文本），并累加该公告的浏览次数；
     * 隐藏/删除/不存在的公告返回"公告不存在"。
     *
     * @param request 详情请求（id 必填）
     * @return 公告详情（数据在 data 字段）
     */
    @Anonymous
    @PostMapping("/detail")
    public AjaxResult detail(@Valid @RequestBody NoticeDetailRequest request)
    {
        NoticeDetailVO data = noticeService.getNoticeDetail(request);
        return AjaxResult.success(data);
    }
}
