package com.aid.aid.controller;

import java.util.List;
import com.aid.aid.domain.AidAiVoiceTag;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.enums.BusinessType;
import com.aid.voice.dto.VoiceTagListRequest;
import com.aid.voice.dto.VoiceTagUpsertRequest;
import com.aid.voice.service.IVoiceTagBusinessService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台音色标签字典 Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/voice-tag")
public class AidAiVoiceTagController extends BaseController
{
    @Resource
    private IVoiceTagBusinessService voiceTagBusinessService;

    /**
     * 分页查询音色标签列表
     */
    @PreAuthorize("@ss.hasPermi('aid:voice-tag:list')")
    @PostMapping("/list")
    public TableDataInfo list(@RequestBody VoiceTagListRequest request)
    {
        startPage();
        List<AidAiVoiceTag> list = voiceTagBusinessService.listVoiceTags(request);
        return getDataTable(list);
    }

    /**
     * 获取音色标签详情
     */
    @PreAuthorize("@ss.hasPermi('aid:voice-tag:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(voiceTagBusinessService.getVoiceTagDetail(id));
    }

    /**
     * 新增音色标签
     */
    @PreAuthorize("@ss.hasPermi('aid:voice-tag:add')")
    @Log(title = "音色标签", businessType = BusinessType.INSERT)
    @PostMapping("/add")
    public AjaxResult add(@Valid @RequestBody VoiceTagUpsertRequest request)
    {
        Long id = voiceTagBusinessService.createVoiceTag(request);
        return success(id);
    }

    /**
     * 修改音色标签
     */
    @PreAuthorize("@ss.hasPermi('aid:voice-tag:edit')")
    @Log(title = "音色标签", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Valid @RequestBody VoiceTagUpsertRequest request)
    {
        voiceTagBusinessService.updateVoiceTag(request);
        return success();
    }

    /**
     * 批量软删除音色标签
     */
    @PreAuthorize("@ss.hasPermi('aid:voice-tag:remove')")
    @Log(title = "音色标签", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        voiceTagBusinessService.deleteVoiceTags(ids);
        return success();
    }
}
