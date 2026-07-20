package com.aid.aid.controller;

import java.util.List;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.aid.domain.AidAudioAsset;
import com.aid.asset.audio.dto.AudioAssetListRequest;
import com.aid.asset.audio.service.IAudioAssetBusinessService;
import com.aid.asset.audio.vo.AudioAssetVO;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.poi.ExcelUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台音频资产 Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/audio-asset")
public class AidAudioAssetController extends BaseController {

    @Resource
    private IAudioAssetBusinessService audioAssetBusinessService;

    /**
     * 后台分页列表（不限用户；默认只返回未删除）。
     */
    @PreAuthorize("@ss.hasPermi('aid:audio-asset:list')")
    @PostMapping("/list")
    public AjaxResult list(@RequestBody AudioAssetListRequest request) {
        IPage<AudioAssetVO> page = audioAssetBusinessService.listForAdmin(request);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("data", page.getRecords());
        ajax.put("total", page.getTotal());
        ajax.put("pageNum", page.getCurrent());
        ajax.put("pageSize", page.getSize());
        return ajax;
    }

    /**
     * 后台资产详情。
     */
    @PreAuthorize("@ss.hasPermi('aid:audio-asset:query')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(audioAssetBusinessService.getDetailForAdmin(id));
    }

    /**
     * 后台批量软删除资产。
     */
    @PreAuthorize("@ss.hasPermi('aid:audio-asset:remove')")
    @Log(title = "音频资产", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        audioAssetBusinessService.deleteForAdmin(ids);
        return success();
    }

    /**
     * 后台导出：与列表接口共用同一套 DTO + 查询条件构造，
     * 保证"列表能看到"的数据"导出也能导出"。
     */
    @PreAuthorize("@ss.hasPermi('aid:audio-asset:export')")
    @Log(title = "音频资产", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, @RequestBody AudioAssetListRequest request) {
        List<AidAudioAsset> list = audioAssetBusinessService.listEntitiesForExport(request);
        ExcelUtil<AidAudioAsset> util = new ExcelUtil<>(AidAudioAsset.class);
        util.exportExcel(response, list, "音频资产数据");
    }
}
