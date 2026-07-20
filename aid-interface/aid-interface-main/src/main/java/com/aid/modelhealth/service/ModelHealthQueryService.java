package com.aid.modelhealth.service;

import com.aid.modelhealth.dto.ModelHealthBoardRequest;
import com.aid.modelhealth.vo.ModelHealthBoardVO;

/**
 * 模型运行状态查询服务。
 *
 * <p>后台管理看板：包含停用模型，返回每格错误摘要，便于运营排查；
 * C端独立看板：仅返回启用中的服务商与模型，不返回错误详情，并使用 60 秒 Redis 缓存。</p>
 *
 * @author 视觉AID
 */
public interface ModelHealthQueryService {

    /**
     * 查询模型运行状态看板（按供应商过滤 + 按模型时间线分页，一次返回该页全部时间轴数据）。
     *
     * @param request   查询入参（providerCode/modelType 可选过滤 + 分页）
     * @param adminView true=后台管理视图（含停用模型与错误详情，不走缓存）；false=仅启用项视图（60秒缓存）
     * @return 看板数据（当前页时间线 + 全量汇总状态）
     */
    ModelHealthBoardVO queryBoard(ModelHealthBoardRequest request, boolean adminView);

    /**
     * 后台监控页健康总览（并入 /aid/modelmonitor/snapshot 返回）：
     * 全量模型（含停用）不分页，附带每格上游错误摘要；结果 30 秒 Redis 缓存，
     * 监控页高频轮询不会反复打库。查询异常时返回 null，由调用方容错，不阻断排队监控主数据。
     *
     * @return 健康看板（全部时间线 + 汇总状态）；异常时为 null
     */
    ModelHealthBoardVO queryAdminOverview();
}
