package com.aid.billing.service;

import com.aid.billing.dto.CreditConsumeQueryRequest;
import com.aid.common.core.domain.AjaxResult;

/**
 * 积分消耗明细查询 Service（C 端）。
 *
 * @author 视觉AID
 */
public interface ICreditConsumeQueryService
{
    /**
     * 分页查询当前用户的积分消耗明细（按业务/任务聚合，含退款信息）。
     *
     * @param request 分页请求
     * @param userId  当前登录用户ID
     * @return AjaxResult，data 为 {@code CreditConsumeRecordVO} 列表，total 为业务总条数
     */
    AjaxResult queryConsumeDetail(CreditConsumeQueryRequest request, Long userId);
}
