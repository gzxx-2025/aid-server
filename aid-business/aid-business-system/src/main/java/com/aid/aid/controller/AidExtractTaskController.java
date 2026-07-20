package com.aid.aid.controller;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.service.IExtractBillingService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 资产提取任务Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/extracttask")
public class AidExtractTaskController extends BaseController
{
    @Autowired
    private IAidExtractTaskService aidExtractTaskService;

    @Autowired
    private IAssetExtractService assetExtractService;

    @Autowired
    private IExtractBillingService extractBillingService;

    /**
     * 查询资产提取任务列表
     */
    @PreAuthorize("@ss.hasPermi('aid:extracttask:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidExtractTask aidExtractTask)
    {
        startPage();
        List<AidExtractTask> list = aidExtractTaskService.selectAidExtractTaskList(aidExtractTask);
        return getDataTable(list);
    }

    /**
     * 导出资产提取任务列表
     */
    @PreAuthorize("@ss.hasPermi('aid:extracttask:export')")
    @Log(title = "资产提取任务", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidExtractTask aidExtractTask)
    {
        List<AidExtractTask> list = aidExtractTaskService.selectAidExtractTaskList(aidExtractTask);
        ExcelUtil<AidExtractTask> util = new ExcelUtil<AidExtractTask>(AidExtractTask.class);
        util.exportExcel(response, list, "资产提取任务数据");
    }

    /**
     * 获取资产提取任务详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:extracttask:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidExtractTaskService.selectAidExtractTaskById(id));
    }

    /**
     * 新增资产提取任务
     * 提取任务由业务入口驱动，禁止 UI 新增，避免绕过扣费 / 状态机。
     */
    @PreAuthorize("@ss.hasPermi('aid:extracttask:add')")
    @Log(title = "资产提取任务", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidExtractTask aidExtractTask)
    {
        return AjaxResult.error(403, "提取任务由业务流水线写入，禁止手动新增");
    }

    /**
     * 修改资产提取任务
     * 禁止改 status / billingTraceId / billingSnapshotJson，避免伪造 SUCCEEDED 跳过退款。
     */
    @PreAuthorize("@ss.hasPermi('aid:extracttask:edit')")
    @Log(title = "资产提取任务", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidExtractTask aidExtractTask)
    {
        return AjaxResult.error(403, "提取任务关键字段与扣费联动，禁止手动修改");
    }

    /**
     * 删除资产提取任务
     * 禁止手动删除，保留审计完整性。
     */
    @PreAuthorize("@ss.hasPermi('aid:extracttask:remove')")
    @Log(title = "资产提取任务", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return AjaxResult.error(403, "提取任务禁止手动删除");
    }

    /**
     * 强制清理僵尸提取任务（管理员操作）
     * 将指定 taskId 的 PENDING/PROCESSING 任务强制标记为 FAILED，退冻结 + 释放 Redis 锁。
     * 用于 consumer 崩溃 / 消息丢失导致任务永远卡住的紧急恢复场景。
     */
    @PreAuthorize("@ss.hasPermi('aid:extracttask:edit')")
    @Log(title = "资产提取任务", businessType = BusinessType.UPDATE)
    @PostMapping("/reclaim/{taskId}")
    public AjaxResult reclaimTask(@PathVariable Long taskId)
    {
        AidExtractTask task = aidExtractTaskService.selectAidExtractTaskById(taskId);
        if (task == null)
        {
            return AjaxResult.error("任务不存在");
        }
        String status = task.getStatus();
        if (!"PENDING".equals(status) && !"PROCESSING".equals(status))
        {
            return AjaxResult.error("任务已终态（" + status + "），无需清理");
        }
        // 委托内部方法做完整清理（标记 FAILED + 退款 + 释放锁）
        try
        {
            forceReclaimSingleTask(task);
            return AjaxResult.success("清理成功，taskId=" + taskId);
        }
        catch (Exception e)
        {
            return AjaxResult.error("清理失败: " + e.getMessage());
        }
    }

    /**
     * 单条任务强制清理（内部方法）
     */
    private void forceReclaimSingleTask(AidExtractTask task)
    {
        Long taskId = task.getId();
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AidExtractTask> update =
                com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.in(AidExtractTask::getStatus, "PENDING", "PROCESSING");
        update.set(AidExtractTask::getStatus, "FAILED");
        update.set(AidExtractTask::getErrorMessage, "管理员手动清理");
        update.set(AidExtractTask::getUpdateTime, new java.util.Date());
        aidExtractTaskService.getBaseMapper().update(null, update);
        if (task.getUserId() != null)
        {
            try { extractBillingService.refundBilling(taskId, task.getUserId()); }
            catch (Exception ex) { /* 已退过或金额为0，忽略 */ }
        }
        assetExtractService.releaseExtractLock(task.getProjectId(), task.getEpisodeId());
    }
}
