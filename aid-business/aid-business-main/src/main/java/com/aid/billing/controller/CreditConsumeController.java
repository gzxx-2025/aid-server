package com.aid.billing.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.billing.dto.CreditConsumeQueryRequest;
import com.aid.billing.service.ICreditConsumeQueryService;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;

/**
 * 积分消耗明细 Controller（C 端，需登录），按业务/任务聚合分页返回当前用户的积分消耗明细。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/credit")
public class CreditConsumeController extends BaseController
{
    @Resource
    private ICreditConsumeQueryService creditConsumeQueryService;

    /**
     * 分页查询当前用户的积分消耗明细。
     * 同一任务的预冻结/结算/退款/补扣多笔变动聚合为一条，展示消耗、退款与净增减金额。
     * 入参 {@code pageNum}（默认1）/ {@code pageSize}（默认10，上限100）；
     * 出参 data 为按业务聚合的消耗记录列表，total 为业务总条数。
     *
     * @param request 分页请求
     * @return 分页消耗明细
     */
    @PostMapping("/consume/list")
    public AjaxResult consumeList(@RequestBody CreditConsumeQueryRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        return creditConsumeQueryService.queryConsumeDetail(request, userId);
    }
}
