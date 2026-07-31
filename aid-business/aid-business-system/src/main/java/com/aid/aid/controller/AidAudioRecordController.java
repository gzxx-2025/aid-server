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
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.service.IAidAudioRecordService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 分镜配音业务记录 Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/audiorecord")
public class AidAudioRecordController extends BaseController
{
    @Autowired
    private IAidAudioRecordService aidAudioRecordService;

    /**
     * 查询配音业务记录列表
     */
    @PreAuthorize("@ss.hasPermi('aid:audiorecord:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidAudioRecord aidAudioRecord)
    {
        startPage();
        List<AidAudioRecord> list = aidAudioRecordService.selectAidAudioRecordList(aidAudioRecord);
        return getDataTable(list);
    }

    /**
     * 导出配音业务记录列表
     */
    @PreAuthorize("@ss.hasPermi('aid:audiorecord:export')")
    @Log(title = "分镜配音业务记录", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidAudioRecord aidAudioRecord)
    {
        List<AidAudioRecord> list = aidAudioRecordService.selectAidAudioRecordList(aidAudioRecord);
        ExcelUtil<AidAudioRecord> util = new ExcelUtil<AidAudioRecord>(AidAudioRecord.class);
        util.exportExcel(response, list, "分镜配音业务记录");
    }

    /**
     * 获取配音业务记录详情
     */
    @PreAuthorize("@ss.hasPermi('aid:audiorecord:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidAudioRecordService.selectAidAudioRecordById(id));
    }

    /**
     * 新增配音业务记录
     * 该表由系统流水线写入（统一任务系统调度），UI 新增会绕过计费与状态机，禁止手动新增。
     */
    @PreAuthorize("@ss.hasPermi('aid:audiorecord:add')")
    @Log(title = "分镜配音业务记录", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidAudioRecord aidAudioRecord)
    {
        return AjaxResult.error(403, "该表由系统流水线写入，禁止手动新增");
    }

    /**
     * 修改配音业务记录
     * 禁止修改 audioUrl / syncVideoUrl / status 等关键产物字段。
     */
    @PreAuthorize("@ss.hasPermi('aid:audiorecord:edit')")
    @Log(title = "分镜配音业务记录", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidAudioRecord aidAudioRecord)
    {
        return AjaxResult.error(403, "该表由系统流水线写入，禁止手动修改");
    }

    /**
     * 删除配音业务记录
     * 禁止手动删除任务记录。
     */
    @PreAuthorize("@ss.hasPermi('aid:audiorecord:remove')")
    @Log(title = "分镜配音业务记录", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return AjaxResult.error(403, "该表由系统流水线写入，禁止手动删除");
    }
}
