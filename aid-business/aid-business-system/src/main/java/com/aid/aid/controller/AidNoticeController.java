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

import com.aid.aid.domain.AidNotice;
import com.aid.aid.service.IAidNoticeService;
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
 * C 端公告 Controller（后台管理端）
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aid/notice")
public class AidNoticeController extends BaseController
{
    /** 标志位：否 */
    private static final String FLAG_NO = "0";
    /** 标志位：是 */
    private static final String FLAG_YES = "1";
    /** 公告类型：系统公告 */
    private static final String NOTICE_TYPE_SYSTEM = "system";
    /** 公告类型：活动公告 */
    private static final String NOTICE_TYPE_ACTIVITY = "activity";
    /** 公告类型：更新公告 */
    private static final String NOTICE_TYPE_UPDATE = "update";

    @Autowired
    private IAidNoticeService aidNoticeService;

    /**
     * 查询公告列表
     */
    @PreAuthorize("@ss.hasPermi('aid:notice:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidNotice aidNotice)
    {
        startPage();
        List<AidNotice> list = aidNoticeService.selectAidNoticeList(aidNotice);
        return getDataTable(list);
    }

    /**
     * 导出公告列表
     */
    @PreAuthorize("@ss.hasPermi('aid:notice:export')")
    @Log(title = "C端公告", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidNotice aidNotice)
    {
        List<AidNotice> list = aidNoticeService.selectAidNoticeList(aidNotice);
        ExcelUtil<AidNotice> util = new ExcelUtil<AidNotice>(AidNotice.class);
        util.exportExcel(response, list, "C端公告数据");
    }

    /**
     * 获取公告详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:notice:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidNoticeService.selectAidNoticeById(id));
    }

    /**
     * 新增公告
     */
    @PreAuthorize("@ss.hasPermi('aid:notice:add')")
    @Log(title = "C端公告", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidNotice aidNotice)
    {
        // 新增：先补默认值再校验（默认值仅在新增时填充，避免编辑时静默覆盖已有字段）
        applyInsertDefaults(aidNotice);
        validate(aidNotice);
        return toAjax(aidNoticeService.insertAidNotice(aidNotice));
    }

    /**
     * 修改公告
     */
    @PreAuthorize("@ss.hasPermi('aid:notice:edit')")
    @Log(title = "C端公告", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidNotice aidNotice)
    {
        if (Objects.isNull(aidNotice) || Objects.isNull(aidNotice.getId()))
        {
            log.error("修改公告失败：主键为空");
            throw new ServiceException("参数不能为空");
        }
        validate(aidNotice);
        return toAjax(aidNoticeService.updateAidNotice(aidNotice));
    }

    /**
     * 删除公告
     */
    @PreAuthorize("@ss.hasPermi('aid:notice:remove')")
    @Log(title = "C端公告", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        log.info("删除公告, operator={}, ids={}", getUsername(), Arrays.toString(ids));
        return toAjax(aidNoticeService.deleteAidNoticeByIds(ids));
    }

    /**
     * 新增时填充默认值（仅作用于新增，避免编辑场景静默覆盖已有字段）：
     * 状态缺省"显示"、排序缺省 0、浏览量缺省 0、是否视频/置顶缺省"否"、
     * 公告类型缺省"系统公告"、发布时间缺省当前时间。
     *
     * @param aidNotice 公告
     */
    private void applyInsertDefaults(AidNotice aidNotice)
    {
        if (Objects.isNull(aidNotice))
        {
            return;
        }
        // 状态缺省按"显示"
        if (StrUtil.isBlank(aidNotice.getStatus()))
        {
            aidNotice.setStatus("0");
        }
        // 排序缺省 0
        if (Objects.isNull(aidNotice.getSortOrder()))
        {
            aidNotice.setSortOrder(0);
        }
        // 浏览量缺省 0
        if (Objects.isNull(aidNotice.getViewCount()))
        {
            aidNotice.setViewCount(0L);
        }
        // 是否视频缺省"否"
        if (StrUtil.isBlank(aidNotice.getIsVideo()))
        {
            aidNotice.setIsVideo(FLAG_NO);
        }
        // 是否置顶缺省"否"
        if (StrUtil.isBlank(aidNotice.getIsTop()))
        {
            aidNotice.setIsTop(FLAG_NO);
        }
        // 公告类型缺省"系统公告"
        if (StrUtil.isBlank(aidNotice.getNoticeType()))
        {
            aidNotice.setNoticeType(NOTICE_TYPE_SYSTEM);
        }
        // 发布时间缺省当前时间
        if (Objects.isNull(aidNotice.getPublishTime()))
        {
            aidNotice.setPublishTime(new Date());
        }
    }

    /**
     * 入参合法性校验（纯校验，不修改入参，避免编辑时把未提交字段改写成默认值）：
     * 标题、内容必填；公告类型/标志位必须在白名单内；视频公告必须带视频地址；时间区间必须先开始后结束。
     *
     * @param aidNotice 公告
     */
    private void validate(AidNotice aidNotice)
    {
        if (Objects.isNull(aidNotice))
        {
            throw new ServiceException("参数不能为空");
        }
        // 标题必填
        if (StrUtil.isBlank(aidNotice.getTitle()))
        {
            throw new ServiceException("标题不能为空");
        }
        // 内容必填
        if (StrUtil.isBlank(aidNotice.getContent()))
        {
            throw new ServiceException("内容不能为空");
        }
        // 公告类型：非空时必须在白名单内
        String noticeType = aidNotice.getNoticeType();
        if (StrUtil.isNotBlank(noticeType)
                && !NOTICE_TYPE_SYSTEM.equals(noticeType)
                && !NOTICE_TYPE_ACTIVITY.equals(noticeType)
                && !NOTICE_TYPE_UPDATE.equals(noticeType))
        {
            log.error("公告类型非法: {}", noticeType);
            throw new ServiceException("公告类型不支持");
        }
        // 是否视频：非空时只能是 0/1
        String isVideo = aidNotice.getIsVideo();
        if (StrUtil.isNotBlank(isVideo) && !FLAG_NO.equals(isVideo) && !FLAG_YES.equals(isVideo))
        {
            log.error("是否视频标志非法: {}", isVideo);
            throw new ServiceException("视频标志不合法");
        }
        // 是否置顶：非空时只能是 0/1
        String isTop = aidNotice.getIsTop();
        if (StrUtil.isNotBlank(isTop) && !FLAG_NO.equals(isTop) && !FLAG_YES.equals(isTop))
        {
            log.error("是否置顶标志非法: {}", isTop);
            throw new ServiceException("置顶标志不合法");
        }
        // 视频公告必须配置视频地址
        if (FLAG_YES.equals(isVideo) && StrUtil.isBlank(aidNotice.getVideoUrl()))
        {
            throw new ServiceException("视频地址不能为空");
        }
        // 时间区间校验
        if (Objects.nonNull(aidNotice.getStartTime()) && Objects.nonNull(aidNotice.getEndTime())
                && aidNotice.getStartTime().after(aidNotice.getEndTime()))
        {
            throw new ServiceException("结束时间需晚于开始时间");
        }
    }
}
