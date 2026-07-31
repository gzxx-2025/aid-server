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
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * AI生图/生视频抽卡记录Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/genrecord")
public class AidGenRecordController extends BaseController
{
    @Autowired
    private IAidGenRecordService aidGenRecordService;

    /**
     * 查询AI生图/生视频抽卡记录列表
     */
    @PreAuthorize("@ss.hasPermi('aid:genrecord:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidGenRecord aidGenRecord)
    {
        startPage();
        List<AidGenRecord> list = aidGenRecordService.selectAidGenRecordList(aidGenRecord);
        return getDataTable(list);
    }

    /**
     * 导出AI生图/生视频抽卡记录列表
     */
    @PreAuthorize("@ss.hasPermi('aid:genrecord:export')")
    @Log(title = "AI生图/生视频抽卡记录", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidGenRecord aidGenRecord)
    {
        List<AidGenRecord> list = aidGenRecordService.selectAidGenRecordList(aidGenRecord);
        ExcelUtil<AidGenRecord> util = new ExcelUtil<AidGenRecord>(AidGenRecord.class);
        util.exportExcel(response, list, "AI生图/生视频抽卡记录数据");
    }

    /**
     * 获取AI生图/生视频抽卡记录详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:genrecord:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidGenRecordService.selectAidGenRecordById(id));
    }

    /**
     * 新增AI生图/生视频抽卡记录
     * 生成记录由系统写入，禁止 UI 层手动新增。
     */
    @PreAuthorize("@ss.hasPermi('aid:genrecord:add')")
    @Log(title = "AI生图/生视频抽卡记录", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidGenRecord aidGenRecord)
    {
        return AjaxResult.error(403, "该表由系统写入，禁止手动新增");
    }

    /**
     * 修改AI生图/生视频抽卡记录
     * 禁止修改 userId / genType / fileUrl / costCredits / isSelected 等关键字段。
     */
    @PreAuthorize("@ss.hasPermi('aid:genrecord:edit')")
    @Log(title = "AI生图/生视频抽卡记录", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidGenRecord aidGenRecord)
    {
        return AjaxResult.error(403, "该表由系统写入，禁止手动修改");
    }

    /**
     * 删除AI生图/生视频抽卡记录
     * 禁止删除生成记录。
     */
    @PreAuthorize("@ss.hasPermi('aid:genrecord:remove')")
    @Log(title = "AI生图/生视频抽卡记录", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return AjaxResult.error(403, "该表由系统写入，禁止手动删除");
    }
}
