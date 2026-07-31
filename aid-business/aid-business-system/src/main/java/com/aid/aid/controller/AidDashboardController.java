package com.aid.aid.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.dashboard.service.IDashboardStatService;
import com.aid.dashboard.vo.DashboardOverviewVO;
import jakarta.annotation.Resource;

/**
 * 后台首页统计Controller
 * 把首页各项业务统计聚合为单接口返回，替代前端逐项 count 的多次请求。
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/dashboard")
public class AidDashboardController extends BaseController
{
    @Resource
    private IDashboardStatService dashboardStatService;

    /**
     * 首页业务概览（一次性返回用户/在线/项目/剧集/分镜/生成/订单等全部计数）
     *
     * @return 概览统计（数据在 data 字段）
     */
    @GetMapping("/overview")
    public AjaxResult overview()
    {
        DashboardOverviewVO vo = dashboardStatService.getOverview();
        return AjaxResult.success(vo);
    }
}
