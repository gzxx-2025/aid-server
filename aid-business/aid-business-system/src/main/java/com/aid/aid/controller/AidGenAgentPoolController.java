package com.aid.aid.controller;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.aid.domain.AidGenAgentPool;
import com.aid.aid.service.IAidGenAgentPoolService;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.SecurityUtils;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.projectgenconfig.matrix.IGenAgentPoolAdminService;
import com.aid.projectgenconfig.matrix.dto.GenPoolSaveCellRequest;
import jakarta.validation.Valid;

/**
 * 生成链路智能体可选池Controller（后台管理）
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/genagentpool")
public class AidGenAgentPoolController extends BaseController
{
    @Autowired
    private IAidGenAgentPoolService aidGenAgentPoolService;

    @Autowired
    private IGenAgentPoolAdminService genAgentPoolAdminService;
    /**
     * 矩阵列表（按 步骤×业务场景×创作模式×剧本类型 聚合成格子）。
     */
    @PreAuthorize("@ss.hasPermi('aid:genagentpool:list')")
    @GetMapping("/matrix")
    public AjaxResult matrix(String step)
    {
        return AjaxResult.success(genAgentPoolAdminService.listMatrix(step));
    }

    /**
     * 某业务场景下可选的智能体 + 模型（供矩阵格子下拉）
     */
    @PreAuthorize("@ss.hasPermi('aid:genagentpool:list')")
    @GetMapping("/options")
    public AjaxResult options(String biz)
    {
        return AjaxResult.success(genAgentPoolAdminService.getOptions(biz));
    }

    /**
     * 覆盖式保存一个矩阵格子
     */
    @PreAuthorize("@ss.hasPermi('aid:genagentpool:edit')")
    @Log(title = "智能体矩阵", businessType = BusinessType.UPDATE)
    @PostMapping("/matrix/save")
    public AjaxResult saveCell(@Valid @RequestBody GenPoolSaveCellRequest request)
    {
        genAgentPoolAdminService.saveCell(request, SecurityUtils.getUsername());
        return AjaxResult.success("保存成功");
    }

    /**
     * 删除一个矩阵格子（软删该组合全部行）
     */
    @PreAuthorize("@ss.hasPermi('aid:genagentpool:remove')")
    @Log(title = "智能体矩阵", businessType = BusinessType.DELETE)
    @PostMapping("/matrix/delete")
    public AjaxResult deleteCell(@RequestBody GenPoolSaveCellRequest request)
    {
        genAgentPoolAdminService.deleteCell(request.getStep(), request.getBizCategoryCode(),
                request.getCreationMode(), request.getScriptType(), SecurityUtils.getUsername());
        return AjaxResult.success("删除成功");
    }
    /**
     * 查询智能体可选池列表
     */
    @PreAuthorize("@ss.hasPermi('aid:genagentpool:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidGenAgentPool aidGenAgentPool)
    {
        startPage();
        List<AidGenAgentPool> list = aidGenAgentPoolService.selectAidGenAgentPoolList(aidGenAgentPool);
        return getDataTable(list);
    }

    /**
     * 导出智能体可选池列表
     */
    @PreAuthorize("@ss.hasPermi('aid:genagentpool:export')")
    @Log(title = "智能体可选池", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidGenAgentPool aidGenAgentPool)
    {
        List<AidGenAgentPool> list = aidGenAgentPoolService.selectAidGenAgentPoolList(aidGenAgentPool);
        ExcelUtil<AidGenAgentPool> util = new ExcelUtil<AidGenAgentPool>(AidGenAgentPool.class);
        util.exportExcel(response, list, "智能体可选池数据");
    }

    /**
     * 获取智能体可选池详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:genagentpool:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidGenAgentPoolService.selectAidGenAgentPoolById(id));
    }

    /**
     * 新增智能体可选池
     */
    @PreAuthorize("@ss.hasPermi('aid:genagentpool:add')")
    @Log(title = "智能体可选池", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidGenAgentPool aidGenAgentPool)
    {
        return toAjax(aidGenAgentPoolService.insertAidGenAgentPool(aidGenAgentPool));
    }

    /**
     * 修改智能体可选池
     */
    @PreAuthorize("@ss.hasPermi('aid:genagentpool:edit')")
    @Log(title = "智能体可选池", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidGenAgentPool aidGenAgentPool)
    {
        return toAjax(aidGenAgentPoolService.updateAidGenAgentPool(aidGenAgentPool));
    }

    /**
     * 删除智能体可选池
     */
    @PreAuthorize("@ss.hasPermi('aid:genagentpool:remove')")
    @Log(title = "智能体可选池", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidGenAgentPoolService.deleteAidGenAgentPoolByIds(ids));
    }
}
