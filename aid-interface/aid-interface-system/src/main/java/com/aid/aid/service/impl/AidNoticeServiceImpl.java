package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidNotice;
import com.aid.aid.mapper.AidNoticeMapper;
import com.aid.aid.service.IAidNoticeService;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.SecurityUtils;

import cn.hutool.core.util.StrUtil;

/**
 * C 端公告 Service 业务层处理（后台管理维护用）
 *
 * @author 视觉AID
 */
@Service
public class AidNoticeServiceImpl extends ServiceImpl<AidNoticeMapper, AidNotice> implements IAidNoticeService
{
    /**
     * 查询公告
     *
     * @param id 公告主键
     * @return 公告
     */
    @Override
    public AidNotice selectAidNoticeById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询公告列表
     *
     * @param aidNotice 公告（标题/类型/状态作为检索条件）
     * @return 公告集合
     */
    @Override
    public List<AidNotice> selectAidNoticeList(AidNotice aidNotice)
    {
        LambdaQueryWrapper<AidNotice> wrapper = Wrappers.lambdaQuery();
        if (Objects.nonNull(aidNotice))
        {
            // 标题模糊检索
            if (StrUtil.isNotBlank(aidNotice.getTitle()))
            {
                wrapper.like(AidNotice::getTitle, aidNotice.getTitle());
            }
            // 公告类型精确检索
            if (StrUtil.isNotBlank(aidNotice.getNoticeType()))
            {
                wrapper.eq(AidNotice::getNoticeType, aidNotice.getNoticeType());
            }
            // 是否视频精确检索
            if (StrUtil.isNotBlank(aidNotice.getIsVideo()))
            {
                wrapper.eq(AidNotice::getIsVideo, aidNotice.getIsVideo());
            }
            // 状态精确检索
            if (StrUtil.isNotBlank(aidNotice.getStatus()))
            {
                wrapper.eq(AidNotice::getStatus, aidNotice.getStatus());
            }
        }
        // 排序：置顶优先，其次 sort_order 升序，相同再按 id 倒序
        wrapper.orderByDesc(AidNotice::getIsTop)
                .orderByAsc(AidNotice::getSortOrder)
                .orderByDesc(AidNotice::getId);
        return this.list(wrapper);
    }

    /**
     * 新增公告
     *
     * @param aidNotice 公告
     * @return 结果
     */
    @Override
    public int insertAidNotice(AidNotice aidNotice)
    {
        // 填充创建时间与创建者
        aidNotice.setCreateTime(DateUtils.getNowDate());
        aidNotice.setCreateBy(SecurityUtils.getUsername());
        return this.save(aidNotice) ? 1 : 0;
    }

    /**
     * 修改公告
     *
     * @param aidNotice 公告
     * @return 结果
     */
    @Override
    public int updateAidNotice(AidNotice aidNotice)
    {
        // 填充更新时间与更新者
        aidNotice.setUpdateTime(DateUtils.getNowDate());
        aidNotice.setUpdateBy(SecurityUtils.getUsername());
        return this.updateById(aidNotice) ? 1 : 0;
    }

    /**
     * 批量删除公告
     *
     * @param ids 需要删除的公告主键
     * @return 结果
     */
    @Override
    public int deleteAidNoticeByIds(Long[] ids)
    {
        if (Objects.isNull(ids) || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除公告信息
     *
     * @param id 公告主键
     * @return 结果
     */
    @Override
    public int deleteAidNoticeById(Long id)
    {
        if (Objects.isNull(id))
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
