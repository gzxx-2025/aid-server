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
import com.aid.aid.domain.AidPromptLib;
import com.aid.aid.domain.dto.SystemPromptUpdateRequest;
import com.aid.aid.service.IAidPromptLibService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 提示词素材库(官方预设与用户自定义)Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/promptlib")
public class AidPromptLibController extends BaseController
{
    @Autowired
    private IAidPromptLibService aidPromptLibService;

    /**
     * 查询提示词素材库(官方预设与用户自定义)列表
     */
    @PreAuthorize("@ss.hasPermi('aid:promptlib:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidPromptLib aidPromptLib)
    {
        startPage();
        List<AidPromptLib> list = aidPromptLibService.selectAidPromptLibList(aidPromptLib);
        return getDataTable(list);
    }

    /**
     * 导出提示词素材库(官方预设与用户自定义)列表
     */
    @PreAuthorize("@ss.hasPermi('aid:promptlib:export')")
    @Log(title = "提示词素材库(官方预设与用户自定义)", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidPromptLib aidPromptLib)
    {
        List<AidPromptLib> list = aidPromptLibService.selectAidPromptLibList(aidPromptLib);
        ExcelUtil<AidPromptLib> util = new ExcelUtil<AidPromptLib>(AidPromptLib.class);
        util.exportExcel(response, list, "提示词素材库(官方预设与用户自定义)数据");
    }

    /**
     * 获取提示词素材库(官方预设与用户自定义)详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:promptlib:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidPromptLibService.selectAidPromptLibById(id));
    }

    /**
     * 新增提示词素材库(官方预设与用户自定义)
     */
    @PreAuthorize("@ss.hasPermi('aid:promptlib:add')")
    @Log(title = "提示词素材库(官方预设与用户自定义)", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidPromptLib aidPromptLib)
    {
        return toAjax(aidPromptLibService.insertAidPromptLib(aidPromptLib));
    }

    /**
     * 修改提示词素材库(官方预设与用户自定义)
     */
    @PreAuthorize("@ss.hasPermi('aid:promptlib:edit')")
    @Log(title = "提示词素材库(官方预设与用户自定义)", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidPromptLib aidPromptLib)
    {
        return toAjax(aidPromptLibService.updateAidPromptLib(aidPromptLib));
    }

    /**
     * 删除提示词素材库(官方预设与用户自定义)
     */
    @PreAuthorize("@ss.hasPermi('aid:promptlib:remove')")
    @Log(title = "提示词素材库(官方预设与用户自定义)", businessType = BusinessType.DELETE)
		@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidPromptLibService.deleteAidPromptLibByIds(ids));
    }
    /**
     * 查询系统提示词列表
     */
    @PreAuthorize("@ss.hasPermi('aid:promptlib:list')")
    @GetMapping("/systemList")
    public AjaxResult systemList(String promptType)
    {
        return success(aidPromptLibService.selectSystemPromptList(promptType));
    }

    /**
     * 修改系统提示词（仅 main_business_prompt / main_teacher_prompt 及版本号）
     */
    @PreAuthorize("@ss.hasPermi('aid:promptlib:edit')")
    @Log(title = "系统提示词管理", businessType = BusinessType.UPDATE)
    @PutMapping("/systemUpdate")
    public AjaxResult systemUpdate(@RequestBody SystemPromptUpdateRequest request)
    {
        try {
            return success(aidPromptLibService.updateSystemPrompt(request));
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 检查系统提示词版本更新状态
     */
    @PreAuthorize("@ss.hasPermi('aid:promptlib:list')")
    @GetMapping("/systemCheckUpdate")
    public AjaxResult systemCheckUpdate()
    {
        return success(aidPromptLibService.checkSystemPromptUpdate());
    }

    /**
     * 根据文件名称获取提示词的历史版本列表（用于回退）
     */
    @PreAuthorize("@ss.hasPermi('aid:promptlib:query')")
    @GetMapping("/systemVersions/{remark}")
    public AjaxResult systemVersions(@PathVariable("remark") String remark)
    {
        return success(aidPromptLibService.getPromptVersionsByRemark(remark));
    }

    /**
     * 拉取系统提示词更新（将本地版本更新到远程最新版本）
     */
    @PreAuthorize("@ss.hasPermi('aid:promptlib:edit')")
    @Log(title = "系统提示词管理-拉取更新", businessType = BusinessType.UPDATE)
    @PutMapping("/systemPullUpdate/{remark}")
    public AjaxResult systemPullUpdate(@PathVariable("remark") String remark)
    {
        try {
            return success(aidPromptLibService.pullSystemPromptUpdate(remark));
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }
}
