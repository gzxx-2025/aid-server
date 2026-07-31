package com.aid.aid.controller;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.aid.domain.AidFaq;
import com.aid.aid.service.IAidFaqService;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.enums.BusinessType;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.poi.ExcelUtil;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 常见问题（FAQ）Controller（后台管理端）
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aid/faq")
public class AidFaqController extends BaseController
{
    @Autowired
    private IAidFaqService aidFaqService;

    /**
     * 查询常见问题列表
     */
    @PreAuthorize("@ss.hasPermi('aid:faq:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidFaq aidFaq)
    {
        startPage();
        List<AidFaq> list = aidFaqService.selectAidFaqList(aidFaq);
        return getDataTable(list);
    }

    /**
     * 导出常见问题列表
     */
    @PreAuthorize("@ss.hasPermi('aid:faq:export')")
    @Log(title = "常见问题", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidFaq aidFaq)
    {
        List<AidFaq> list = aidFaqService.selectAidFaqList(aidFaq);
        ExcelUtil<AidFaq> util = new ExcelUtil<AidFaq>(AidFaq.class);
        util.exportExcel(response, list, "常见问题数据");
    }

    /**
     * 获取常见问题详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:faq:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidFaqService.selectAidFaqById(id));
    }

    /**
     * 新增常见问题
     */
    @PreAuthorize("@ss.hasPermi('aid:faq:add')")
    @Log(title = "常见问题", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidFaq aidFaq)
    {
        applyInsertDefaults(aidFaq);
        validate(aidFaq);
        return toAjax(aidFaqService.insertAidFaq(aidFaq));
    }

    /**
     * 修改常见问题
     */
    @PreAuthorize("@ss.hasPermi('aid:faq:edit')")
    @Log(title = "常见问题", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidFaq aidFaq)
    {
        if (Objects.isNull(aidFaq) || Objects.isNull(aidFaq.getId()))
        {
            log.error("修改常见问题失败：主键为空");
            throw new ServiceException("参数不能为空");
        }
        validate(aidFaq);
        return toAjax(aidFaqService.updateAidFaq(aidFaq));
    }

    /**
     * 删除常见问题
     */
    @PreAuthorize("@ss.hasPermi('aid:faq:remove')")
    @Log(title = "常见问题", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        log.info("删除常见问题, operator={}, ids={}", getUsername(), Arrays.toString(ids));
        return toAjax(aidFaqService.deleteAidFaqByIds(ids));
    }

    /**
     * 新增时填充默认值（仅作用于新增，避免编辑场景静默覆盖已有字段）：
     * 状态缺省"显示"、排序缺省 0、浏览量缺省 0、发布时间缺省当前时间。
     *
     * @param aidFaq 常见问题
     */
    private void applyInsertDefaults(AidFaq aidFaq)
    {
        if (Objects.isNull(aidFaq))
        {
            return;
        }
        if (StrUtil.isBlank(aidFaq.getStatus()))
        {
            aidFaq.setStatus("0");
        }
        if (Objects.isNull(aidFaq.getSortOrder()))
        {
            aidFaq.setSortOrder(0);
        }
        if (Objects.isNull(aidFaq.getViewCount()))
        {
            aidFaq.setViewCount(0L);
        }
        if (Objects.isNull(aidFaq.getPublishTime()))
        {
            aidFaq.setPublishTime(new Date());
        }
    }

    /**
     * 入参合法性校验（纯校验，不修改入参）：标题、内容必填。
     *
     * @param aidFaq 常见问题
     */
    private void validate(AidFaq aidFaq)
    {
        if (Objects.isNull(aidFaq))
        {
            throw new ServiceException("参数不能为空");
        }
        if (StrUtil.isBlank(aidFaq.getTitle()))
        {
            throw new ServiceException("标题不能为空");
        }
        if (StrUtil.isBlank(aidFaq.getContent()))
        {
            throw new ServiceException("内容不能为空");
        }
    }
}
