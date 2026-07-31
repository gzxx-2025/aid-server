package com.aid.banner.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.banner.dto.HomeBannerListRequest;
import com.aid.banner.service.IHomeBannerService;
import com.aid.banner.vo.HomeBannerVO;
import com.aid.common.annotation.Anonymous;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 首页 Banner Controller（C 端只读）。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/home/banner")
public class HomeBannerController extends BaseController
{
    @Resource
    private IHomeBannerService homeBannerService;

    /**
     * 分页查询首页可展示的 Banner 列表
     * 返回当前已显示且处于生效时间区间内的 Banner，按 sortOrder 升序。
     *
     * @param request 查询请求（pageNum/pageSize 可选）
     * @return Banner 分页结果（data 为列表，附 total/pageNum/pageSize）
     */
    @Anonymous
    @PostMapping("/list")
    public AjaxResult list(@RequestBody(required = false) HomeBannerListRequest request)
    {
        IPage<HomeBannerVO> page = homeBannerService.listEnabledBanners(request);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("data", page.getRecords());
        ajax.put("total", page.getTotal());
        ajax.put("pageNum", page.getCurrent());
        ajax.put("pageSize", page.getSize());
        return ajax;
    }
}
