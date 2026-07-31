package com.aid.banner.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aid.aid.domain.AidHomeBanner;
import com.aid.aid.service.IAidHomeBannerService;
import com.aid.banner.dto.HomeBannerListRequest;
import com.aid.banner.service.IHomeBannerService;
import com.aid.banner.vo.HomeBannerVO;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 首页 Banner - C 端只读 Service 实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class HomeBannerServiceImpl implements IHomeBannerService
{
    /** 状态：显示 */
    private static final String STATUS_SHOW = "0";
    /** 删除标志：未删除 */
    private static final String DEL_FLAG_NORMAL = "0";
    /** 跳转类型：无跳转 */
    private static final String LINK_TYPE_NONE = "none";
    /** 跳转类型：外部链接 */
    private static final String LINK_TYPE_EXTERNAL = "external";
    /** 跳转类型：内部页面 */
    private static final String LINK_TYPE_INTERNAL = "internal";
    /** 默认页码 */
    private static final int DEFAULT_PAGE_NUM = 1;
    /** 默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 10;
    /** 每页最大条数 */
    private static final int MAX_PAGE_SIZE = 50;

    @Resource
    private IAidHomeBannerService aidHomeBannerService;

    /**
     * 分页查询当前可展示的首页 Banner 列表
     * 仅查询字段：id, title, summary, bannerType, resourceUrl, linkType, linkUrl, sortOrder
     */
    @Override
    public IPage<HomeBannerVO> listEnabledBanners(HomeBannerListRequest request)
    {
        int pageNum = DEFAULT_PAGE_NUM;
        int pageSize = DEFAULT_PAGE_SIZE;
        if (Objects.nonNull(request))
        {
            if (Objects.nonNull(request.getPageNum()) && request.getPageNum() >= 1)
            {
                pageNum = request.getPageNum();
            }
            if (Objects.nonNull(request.getPageSize()) && request.getPageSize() >= 1)
            {
                pageSize = Math.min(request.getPageSize(), MAX_PAGE_SIZE);
            }
        }

        Date now = new Date();
        // 仅返回必要字段，减少数据列返回
        LambdaQueryWrapper<AidHomeBanner> wrapper = Wrappers.lambdaQuery();
        wrapper.select(
                AidHomeBanner::getId,
                AidHomeBanner::getTitle,
                AidHomeBanner::getSummary,
                AidHomeBanner::getBannerType,
                AidHomeBanner::getResourceUrl,
                AidHomeBanner::getCoverUrl,
                AidHomeBanner::getLinkType,
                AidHomeBanner::getLinkUrl,
                AidHomeBanner::getSortOrder
        );
        wrapper.eq(AidHomeBanner::getStatus, STATUS_SHOW);
        wrapper.eq(AidHomeBanner::getDelFlag, DEL_FLAG_NORMAL);
        // 生效开始时间：为空或 <= 当前时间
        wrapper.and(w -> w.isNull(AidHomeBanner::getStartTime).or().le(AidHomeBanner::getStartTime, now));
        // 生效结束时间：为空或 >= 当前时间
        wrapper.and(w -> w.isNull(AidHomeBanner::getEndTime).or().ge(AidHomeBanner::getEndTime, now));
        wrapper.orderByAsc(AidHomeBanner::getSortOrder).orderByDesc(AidHomeBanner::getId);

        Page<AidHomeBanner> page = new Page<>(pageNum, pageSize);
        IPage<AidHomeBanner> result = aidHomeBannerService.page(page, wrapper);

        List<HomeBannerVO> voList = new ArrayList<>();
        for (AidHomeBanner entity : result.getRecords())
        {
            voList.add(toVO(entity));
        }
        Page<HomeBannerVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    /** 实体转 C 端展示 VO */
    private HomeBannerVO toVO(AidHomeBanner entity)
    {
        // 跳转类型归一化：空值按"无跳转"返回，避免前端拿到 null 难以判断
        String linkType = StrUtil.isBlank(entity.getLinkType()) ? LINK_TYPE_NONE : entity.getLinkType();
        // 仅在真实跳转（external/internal）时返回跳转地址；无跳转时置空，
        // 与接口文档约定一致，防止"无跳转"残留旧 linkUrl 误导前端点击跳转
        boolean hasJump = LINK_TYPE_EXTERNAL.equals(linkType) || LINK_TYPE_INTERNAL.equals(linkType);
        String linkUrl = hasJump ? entity.getLinkUrl() : null;
        return HomeBannerVO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .summary(entity.getSummary())
                .bannerType(entity.getBannerType())
                .resourceUrl(entity.getResourceUrl())
                .coverUrl(entity.getCoverUrl())
                .linkType(linkType)
                .linkUrl(linkUrl)
                .sortOrder(entity.getSortOrder())
                .build();
    }
}
