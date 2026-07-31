package com.aid.faq.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.common.annotation.Anonymous;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.faq.dto.FaqDetailRequest;
import com.aid.faq.dto.FaqListRequest;
import com.aid.faq.service.IFaqService;
import com.aid.faq.vo.FaqDetailVO;
import com.aid.faq.vo.FaqListItemVO;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 常见问题 Controller（C 端只读）
 *
 * 本模块仅提供查询能力，不对外提供新增/修改/删除接口；
 * 常见问题由后台管理员在 {@code aid_faq} 中维护。接口匿名可访问，便于帮助中心未登录浏览。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/faq")
public class FaqController extends BaseController
{
    @Resource
    private IFaqService faqService;

    /**
     * 分页查询常见问题列表
     * 仅返回已显示的问题，按 sortOrder 升序；列表不含完整内容，仅含标题/分类/发布时间等摘要。
     *
     * @param request 查询请求（pageNum/pageSize/category/keyword 均可选）
     * @return 分页结果（data 为列表，附 total/pageNum/pageSize）
     */
    @Anonymous
    @PostMapping("/list")
    public AjaxResult list(@RequestBody(required = false) FaqListRequest request)
    {
        IPage<FaqListItemVO> page = faqService.listFaqs(request);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("data", page.getRecords());
        ajax.put("total", page.getTotal());
        ajax.put("pageNum", page.getCurrent());
        ajax.put("pageSize", page.getSize());
        return ajax;
    }

    /**
     * 查询常见问题详情
     * 返回问题完整内容，并累加该问题的浏览次数。
     *
     * @param request 详情请求（id 必填）
     * @return 详情（数据在 data 字段）
     */
    @Anonymous
    @PostMapping("/detail")
    public AjaxResult detail(@Valid @RequestBody FaqDetailRequest request)
    {
        FaqDetailVO data = faqService.getFaqDetail(request);
        return AjaxResult.success(data);
    }
}
