package com.aid.notice.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aid.aid.domain.AidNotice;
import com.aid.aid.service.IAidNoticeService;
import com.aid.common.exception.ServiceException;
import com.aid.notice.dto.NoticeDetailRequest;
import com.aid.notice.dto.NoticeListRequest;
import com.aid.notice.service.INoticeService;
import com.aid.notice.vo.NoticeDetailVO;
import com.aid.notice.vo.NoticeListItemVO;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 公告 - C 端只读 Service 实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class NoticeServiceImpl implements INoticeService
{
    /** 状态：显示 */
    private static final String STATUS_SHOW = "0";
    /** 删除标志：未删除 */
    private static final String DEL_FLAG_NORMAL = "0";
    /** 标志位：是（视频/置顶通用） */
    private static final String FLAG_YES = "1";
    /** 默认页码 */
    private static final int DEFAULT_PAGE_NUM = 1;
    /** 默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 10;
    /** 每页最大条数（防止恶意拉全表） */
    private static final int MAX_PAGE_SIZE = 50;

    @Resource
    private IAidNoticeService aidNoticeService;

    /**
     * 分页查询当前可展示的公告列表（不含 content 大字段）
     * 仅查询字段：id, title, description, imageUrl, isVideo, videoUrl, noticeType, isTop, sortOrder, viewCount, publishTime
     */
    @Override
    public IPage<NoticeListItemVO> listNotices(NoticeListRequest request)
    {
        NoticeListRequest req = Objects.isNull(request) ? new NoticeListRequest() : request;
        int pageNum = Objects.isNull(req.getPageNum()) || req.getPageNum() < 1 ? DEFAULT_PAGE_NUM : req.getPageNum();
        int pageSize = normalizePageSize(req.getPageSize());

        Date now = new Date();
        // 仅返回列表必要字段，不含 content 大字段
        LambdaQueryWrapper<AidNotice> wrapper = Wrappers.lambdaQuery();
        wrapper.select(
                AidNotice::getId,
                AidNotice::getTitle,
                AidNotice::getDescription,
                AidNotice::getImageUrl,
                AidNotice::getIsVideo,
                AidNotice::getVideoUrl,
                AidNotice::getNoticeType,
                AidNotice::getIsTop,
                AidNotice::getSortOrder,
                AidNotice::getViewCount,
                AidNotice::getPublishTime
        );
        wrapper.eq(AidNotice::getStatus, STATUS_SHOW);
        wrapper.eq(AidNotice::getDelFlag, DEL_FLAG_NORMAL);
        // 公告类型过滤（可选）
        if (StrUtil.isNotBlank(req.getNoticeType()))
        {
            wrapper.eq(AidNotice::getNoticeType, req.getNoticeType());
        }
        // 标题关键字模糊搜索（可选）
        if (StrUtil.isNotBlank(req.getKeyword()))
        {
            wrapper.like(AidNotice::getTitle, req.getKeyword());
        }
        // 生效开始时间：为空或 <= 当前时间
        wrapper.and(w -> w.isNull(AidNotice::getStartTime).or().le(AidNotice::getStartTime, now));
        // 生效结束时间：为空或 >= 当前时间
        wrapper.and(w -> w.isNull(AidNotice::getEndTime).or().ge(AidNotice::getEndTime, now));
        // 排序：置顶优先，其次 sortOrder 升序，相同再按 id 倒序（新公告靠前）
        wrapper.orderByDesc(AidNotice::getIsTop)
                .orderByAsc(AidNotice::getSortOrder)
                .orderByDesc(AidNotice::getId);

        Page<AidNotice> page = new Page<>(pageNum, pageSize);
        IPage<AidNotice> result = aidNoticeService.page(page, wrapper);

        List<NoticeListItemVO> voList = new ArrayList<>();
        for (AidNotice entity : result.getRecords())
        {
            voList.add(toListItemVO(entity));
        }
        Page<NoticeListItemVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 查询公告详情（含完整内容），命中后累加浏览次数
     * 仅查询字段：id, title, description, imageUrl, isVideo, videoUrl, content, noticeType, isTop, viewCount, publishTime
     */
    @Override
    public NoticeDetailVO getNoticeDetail(NoticeDetailRequest request)
    {
        if (Objects.isNull(request) || Objects.isNull(request.getId()))
        {
            log.error("公告详情查询入参非法: {}", request);
            throw new ServiceException("参数错误");
        }

        // 仅返回必要字段
        LambdaQueryWrapper<AidNotice> wrapper = Wrappers.lambdaQuery();
        wrapper.select(
                AidNotice::getId,
                AidNotice::getTitle,
                AidNotice::getDescription,
                AidNotice::getImageUrl,
                AidNotice::getIsVideo,
                AidNotice::getVideoUrl,
                AidNotice::getContent,
                AidNotice::getNoticeType,
                AidNotice::getIsTop,
                AidNotice::getViewCount,
                AidNotice::getPublishTime
        );
        wrapper.eq(AidNotice::getId, request.getId());
        wrapper.eq(AidNotice::getStatus, STATUS_SHOW);
        wrapper.eq(AidNotice::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.last("LIMIT 1");

        AidNotice entity = aidNoticeService.getOne(wrapper);
        if (Objects.isNull(entity))
        {
            log.error("公告不存在或未显示, id={}", request.getId());
            throw new ServiceException("公告不存在");
        }

        // 累加浏览次数（原子自增，避免读改写并发丢更新；失败不影响详情返回）
        try
        {
            UpdateWrapper<AidNotice> updateWrapper = new UpdateWrapper<>();
            updateWrapper.setSql("view_count = view_count + 1");
            updateWrapper.eq("id", entity.getId());
            aidNoticeService.update(null, updateWrapper);
        }
        catch (Exception e)
        {
            log.error("公告浏览次数自增失败, id={}", entity.getId(), e);
        }

        boolean isVideo = Objects.equals(FLAG_YES, entity.getIsVideo());
        // 出参回显累加后的浏览次数（原值 + 1，避免再查一次库）
        long viewCount = (Objects.isNull(entity.getViewCount()) ? 0L : entity.getViewCount()) + 1;
        return NoticeDetailVO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .imageUrl(entity.getImageUrl())
                .isVideo(isVideo)
                // 非视频公告不下发视频地址，防止残留旧值误导前端渲染播放器
                .videoUrl(isVideo ? entity.getVideoUrl() : null)
                .content(entity.getContent())
                .noticeType(entity.getNoticeType())
                .isTop(Objects.equals(FLAG_YES, entity.getIsTop()))
                .viewCount(viewCount)
                .publishTime(entity.getPublishTime())
                .build();
    }

    /** 实体转列表项 VO（char 标志位归一化为 Boolean，前端免转换） */
    private NoticeListItemVO toListItemVO(AidNotice entity)
    {
        boolean isVideo = Objects.equals(FLAG_YES, entity.getIsVideo());
        return NoticeListItemVO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .imageUrl(entity.getImageUrl())
                .isVideo(isVideo)
                // 非视频公告不下发视频地址
                .videoUrl(isVideo ? entity.getVideoUrl() : null)
                .noticeType(entity.getNoticeType())
                .isTop(Objects.equals(FLAG_YES, entity.getIsTop()))
                .sortOrder(entity.getSortOrder())
                .viewCount(entity.getViewCount())
                .publishTime(entity.getPublishTime())
                .build();
    }

    /** 归一化每页条数：缺省 10，限制在 1..MAX_PAGE_SIZE */
    private int normalizePageSize(Integer pageSize)
    {
        if (Objects.isNull(pageSize) || pageSize < 1)
        {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
