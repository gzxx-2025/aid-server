package com.aid.dashboard.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 后台首页业务概览统计VO（一次性聚合返回，替代前端多次 count 请求）
 *
 * @author 视觉AID
 */
@Data
@Builder
public class DashboardOverviewVO {

    /** 平台用户总数（系统用户） */
    private Long userTotal;

    /** 启用用户数（status=0） */
    private Long userEnabled;

    /** 在线用户数（Redis 登录令牌数） */
    private Long online;

    /** 漫剧项目总数 */
    private Long projectTotal;

    /** 制作中项目数（status=1） */
    private Long projectMaking;

    /** 剧集总数 */
    private Long episodeTotal;

    /** 分镜总数 */
    private Long storyboardTotal;

    /** 生成记录总数 */
    private Long genTotal;

    /** 生成成功数（status=1） */
    private Long genSuccess;

    /** 生成处理中数（status=0） */
    private Long genProcessing;

    /** 生成失败数（status=2） */
    private Long genFailed;

    /** 已支付订单数（pay_status=paid） */
    private Long orderPaid;

    /** 待支付订单数（pay_status=pending） */
    private Long orderPending;
}
