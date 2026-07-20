package com.aid.aid.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.aid.domain.vo.AidInviteRebateRecordVo;
import com.aid.aid.service.IAidInviteRebateRecordService;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;

/**
 * 邀请返佣记录Controller（后台管理）
 * 返佣记录由支付回调自动产生（发放/退款撤回），属审计数据，后台仅允许查询，禁止任何增删改。
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/inviterebate")
public class AidInviteRebateRecordController extends BaseController
{
    @Autowired
    private IAidInviteRebateRecordService aidInviteRebateRecordService;

    /**
     * 查询邀请返佣记录列表（联表返回邀请人/被邀请人昵称）
     */
    @PreAuthorize("@ss.hasPermi('aid:inviterebate:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidInviteRebateRecordVo query)
    {
        startPage();
        List<AidInviteRebateRecordVo> list = aidInviteRebateRecordService.selectRebateRecordVoList(query);
        return getDataTable(list);
    }

    /**
     * 获取邀请返佣记录详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:inviterebate:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidInviteRebateRecordService.getById(id));
    }
}
