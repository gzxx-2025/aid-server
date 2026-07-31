package com.aid.aid.controller;

import java.util.Arrays;
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

import com.aid.aid.domain.AidHomeBanner;
import com.aid.aid.service.IAidHomeBannerService;
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
 * 首页 Banner 配置 Controller（后台管理端）。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aid/homebanner")
public class AidHomeBannerController extends BaseController
{
    /** 资源类型：图片 */
    private static final String BANNER_TYPE_IMAGE = "image";
    /** 资源类型：视频 */
    private static final String BANNER_TYPE_VIDEO = "video";
    /** 资源类型：动图 */
    private static final String BANNER_TYPE_GIF = "gif";
    /** 跳转类型：外部链接 */
    private static final String LINK_TYPE_EXTERNAL = "external";
    /** 跳转类型：内部页面 */
    private static final String LINK_TYPE_INTERNAL = "internal";
    /** 跳转类型：无跳转 */
    private static final String LINK_TYPE_NONE = "none";

    @Autowired
    private IAidHomeBannerService aidHomeBannerService;

    /**
     * 查询首页 Banner 配置列表
     */
    @PreAuthorize("@ss.hasPermi('aid:homebanner:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidHomeBanner aidHomeBanner)
    {
        startPage();
        List<AidHomeBanner> list = aidHomeBannerService.selectAidHomeBannerList(aidHomeBanner);
        return getDataTable(list);
    }

    /**
     * 导出首页 Banner 配置列表
     */
    @PreAuthorize("@ss.hasPermi('aid:homebanner:export')")
    @Log(title = "首页Banner配置", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidHomeBanner aidHomeBanner)
    {
        List<AidHomeBanner> list = aidHomeBannerService.selectAidHomeBannerList(aidHomeBanner);
        ExcelUtil<AidHomeBanner> util = new ExcelUtil<AidHomeBanner>(AidHomeBanner.class);
        util.exportExcel(response, list, "首页Banner配置数据");
    }

    /**
     * 获取首页 Banner 配置详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:homebanner:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidHomeBannerService.selectAidHomeBannerById(id));
    }

    /**
     * 新增首页 Banner 配置
     */
    @PreAuthorize("@ss.hasPermi('aid:homebanner:add')")
    @Log(title = "首页Banner配置", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidHomeBanner aidHomeBanner)
    {
        // 新增：先补默认值再校验（默认值仅在新增时填充，避免编辑时静默覆盖已有字段）
        applyInsertDefaults(aidHomeBanner);
        validate(aidHomeBanner);
        return toAjax(aidHomeBannerService.insertAidHomeBanner(aidHomeBanner));
    }

    /**
     * 修改首页 Banner 配置
     */
    @PreAuthorize("@ss.hasPermi('aid:homebanner:edit')")
    @Log(title = "首页Banner配置", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidHomeBanner aidHomeBanner)
    {
        if (Objects.isNull(aidHomeBanner) || Objects.isNull(aidHomeBanner.getId()))
        {
            log.error("修改首页Banner失败：主键为空");
            throw new ServiceException("参数不能为空");
        }
        validate(aidHomeBanner);
        return toAjax(aidHomeBannerService.updateAidHomeBanner(aidHomeBanner));
    }

    /**
     * 删除首页 Banner 配置
     */
    @PreAuthorize("@ss.hasPermi('aid:homebanner:remove')")
    @Log(title = "首页Banner配置", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        log.info("删除首页Banner, operator={}, ids={}", getUsername(), Arrays.toString(ids));
        return toAjax(aidHomeBannerService.deleteAidHomeBannerByIds(ids));
    }

    /**
     * 新增时填充默认值（仅作用于新增，避免编辑场景静默覆盖已有字段）：
     * 状态缺省"显示"、排序缺省 0、跳转类型缺省"无跳转"。
     *
     * @param aidHomeBanner 首页 Banner 配置
     */
    private void applyInsertDefaults(AidHomeBanner aidHomeBanner)
    {
        if (Objects.isNull(aidHomeBanner))
        {
            return;
        }
        // 状态缺省按"显示"
        if (StrUtil.isBlank(aidHomeBanner.getStatus()))
        {
            aidHomeBanner.setStatus("0");
        }
        // 排序缺省 0
        if (Objects.isNull(aidHomeBanner.getSortOrder()))
        {
            aidHomeBanner.setSortOrder(0);
        }
        // 跳转类型缺省"无跳转"
        if (StrUtil.isBlank(aidHomeBanner.getLinkType()))
        {
            aidHomeBanner.setLinkType(LINK_TYPE_NONE);
        }
    }

    /**
     * 入参合法性校验（纯校验，不修改入参，避免编辑时把未提交字段改写成默认值）：
     * 标题、资源类型、资源地址必填；选择跳转时必须带跳转地址；时间区间必须先开始后结束。
     *
     * @param aidHomeBanner 首页 Banner 配置
     */
    private void validate(AidHomeBanner aidHomeBanner)
    {
        if (Objects.isNull(aidHomeBanner))
        {
            throw new ServiceException("参数不能为空");
        }
        // 标题必填
        if (StrUtil.isBlank(aidHomeBanner.getTitle()))
        {
            throw new ServiceException("标题不能为空");
        }
        // 资源类型必填且在白名单内
        String type = aidHomeBanner.getBannerType();
        if (StrUtil.isBlank(type)
                || (!BANNER_TYPE_IMAGE.equals(type) && !BANNER_TYPE_VIDEO.equals(type) && !BANNER_TYPE_GIF.equals(type)))
        {
            throw new ServiceException("资源类型不支持");
        }
        // 资源地址必填
        if (StrUtil.isBlank(aidHomeBanner.getResourceUrl()))
        {
            throw new ServiceException("资源地址不能为空");
        }
        // 跳转类型：仅校验合法性，不回写入参；缺省按"无跳转"参与后续判断
        String linkType = StrUtil.isBlank(aidHomeBanner.getLinkType()) ? LINK_TYPE_NONE : aidHomeBanner.getLinkType();
        if (!LINK_TYPE_NONE.equals(linkType) && !LINK_TYPE_EXTERNAL.equals(linkType) && !LINK_TYPE_INTERNAL.equals(linkType))
        {
            throw new ServiceException("跳转类型不支持");
        }
        // 选择跳转时必须配置链接地址，并按类型校验地址协议，防止伪协议(javascript:)XSS / 开放重定向
        if (LINK_TYPE_EXTERNAL.equals(linkType) || LINK_TYPE_INTERNAL.equals(linkType))
        {
            String linkUrl = aidHomeBanner.getLinkUrl();
            if (StrUtil.isBlank(linkUrl))
            {
                throw new ServiceException("跳转地址不能为空");
            }
            String trimmed = linkUrl.trim();
            String lower = trimmed.toLowerCase();
            if (LINK_TYPE_EXTERNAL.equals(linkType))
            {
                // 外链必须是 http(s)，拒绝 javascript:/data: 等伪协议
                if (!lower.startsWith("http://") && !lower.startsWith("https://"))
                {
                    throw new ServiceException("外链格式有误");
                }
            }
            else
            {
                // 站内跳转必须是单个 / 开头的相对路径，拒绝协议相对(//、/\)与站外绝对地址
                if (!trimmed.startsWith("/") || trimmed.startsWith("//") || trimmed.startsWith("/\\"))
                {
                    throw new ServiceException("站内地址格式有误");
                }
            }
        }
        // 时间区间校验
        if (Objects.nonNull(aidHomeBanner.getStartTime()) && Objects.nonNull(aidHomeBanner.getEndTime())
                && aidHomeBanner.getStartTime().after(aidHomeBanner.getEndTime()))
        {
            throw new ServiceException("结束时间需晚于开始时间");
        }
    }
}
