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
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 剧集信息Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aid/aidcomicepisode")
public class AidComicEpisodeController extends BaseController
{
    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;

    /**
     * 查询剧集信息列表
     */
    @PreAuthorize("@ss.hasPermi('aid:aidcomicepisode:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidComicEpisode aidComicEpisode)
    {
        startPage();
        List<AidComicEpisode> list = aidComicEpisodeService.selectAidComicEpisodeList(aidComicEpisode);
        return getDataTable(list);
    }

    /**
     * 导出剧集信息列表
     */
    @PreAuthorize("@ss.hasPermi('aid:aidcomicepisode:export')")
    @Log(title = "剧集信息", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidComicEpisode aidComicEpisode)
    {
        List<AidComicEpisode> list = aidComicEpisodeService.selectAidComicEpisodeList(aidComicEpisode);
        ExcelUtil<AidComicEpisode> util = new ExcelUtil<AidComicEpisode>(AidComicEpisode.class);
        util.exportExcel(response, list, "剧集信息数据");
    }

    /**
     * 获取剧集信息详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:aidcomicepisode:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidComicEpisodeService.selectAidComicEpisodeById(id));
    }

    /**
     * 新增剧集信息
     */
    @PreAuthorize("@ss.hasPermi('aid:aidcomicepisode:add')")
    @Log(title = "剧集信息", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidComicEpisode aidComicEpisode)
    {
        return toAjax(aidComicEpisodeService.insertAidComicEpisode(aidComicEpisode));
    }

    /**
     * 修改剧集信息
     */
    @PreAuthorize("@ss.hasPermi('aid:aidcomicepisode:edit')")
    @Log(title = "剧集信息", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidComicEpisode aidComicEpisode)
    {
        return toAjax(aidComicEpisodeService.updateAidComicEpisode(aidComicEpisode));
    }

    /**
     * 删除剧集信息
     */
    @PreAuthorize("@ss.hasPermi('aid:aidcomicepisode:remove')")
    @Log(title = "剧集信息", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidComicEpisodeService.deleteAidComicEpisodeByIds(ids));
    }
}
