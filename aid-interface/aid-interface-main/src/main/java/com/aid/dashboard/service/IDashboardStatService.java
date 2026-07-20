package com.aid.dashboard.service;

import com.aid.dashboard.vo.DashboardOverviewVO;

/**
 * 后台首页统计聚合Service接口
 *
 * @author 视觉AID
 */
public interface IDashboardStatService {

    /**
     * 查询后台首页业务概览（一次性聚合所有计数，替代前端多次 count 请求）
     *
     * @return 概览统计
     */
    DashboardOverviewVO getOverview();
}
