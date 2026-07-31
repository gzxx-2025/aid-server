package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import cn.hutool.core.util.StrUtil;
import com.aid.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.mapper.AidStoryboardMapper;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidStoryboardService;

/**
 * 分镜时间轴主Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidStoryboardServiceImpl extends ServiceImpl<AidStoryboardMapper, AidStoryboard> implements IAidStoryboardService
{
    @Autowired
    private AidStoryboardMapper aidStoryboardMapper;

    /**
     * 查询分镜时间轴主
     *
     * @param id 分镜时间轴主主键
     * @return 分镜时间轴主
     */
    @Override
    public AidStoryboard selectAidStoryboardById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询分镜时间轴主列表
     *
     * @param aidStoryboard 分镜时间轴主
     * @return 分镜时间轴主
     */
    @Override
    public List<AidStoryboard> selectAidStoryboardList(AidStoryboard aidStoryboard)
    {
        LambdaQueryWrapper<AidStoryboard> wrapper = Wrappers.lambdaQuery();
        if (aidStoryboard != null)
        {
            if (aidStoryboard.getProjectId() != null)
            {
                wrapper.eq(AidStoryboard::getProjectId, aidStoryboard.getProjectId());
            }
            if (aidStoryboard.getEpisodeId() != null)
            {
                wrapper.eq(AidStoryboard::getEpisodeId, aidStoryboard.getEpisodeId());
            }
            if (aidStoryboard.getSourceSceneId() != null)
            {
                wrapper.eq(AidStoryboard::getSourceSceneId, aidStoryboard.getSourceSceneId());
            }
            if (aidStoryboard.getUserId() != null)
            {
                wrapper.eq(AidStoryboard::getUserId, aidStoryboard.getUserId());
            }
            if (aidStoryboard.getBatchId() != null)
            {
                wrapper.eq(AidStoryboard::getBatchId, aidStoryboard.getBatchId());
            }
            if (StrUtil.isNotBlank(aidStoryboard.getSourceSceneCode()))
            {
                wrapper.eq(AidStoryboard::getSourceSceneCode, aidStoryboard.getSourceSceneCode());
            }
            if (StrUtil.isNotBlank(aidStoryboard.getTitle()))
            {
                wrapper.like(AidStoryboard::getTitle, aidStoryboard.getTitle());
            }
        }
        wrapper.orderByAsc(AidStoryboard::getProjectId)
                .orderByAsc(AidStoryboard::getEpisodeId)
                .orderByAsc(AidStoryboard::getSourceSceneCode)
                .orderByAsc(AidStoryboard::getSortOrder);
        return this.list(wrapper);
    }

    /**
     * 新增分镜时间轴主
     *
     * @param aidStoryboard 分镜时间轴主
     * @return 结果
     */
    @Override
    public int insertAidStoryboard(AidStoryboard aidStoryboard)
    {
        aidStoryboard.setCreateTime(DateUtils.getNowDate());
        return this.save(aidStoryboard) ? 1 : 0;
    }

    /**
     * 修改分镜时间轴主
     *
     * @param aidStoryboard 分镜时间轴主
     * @return 结果
     */
    @Override
    public int updateAidStoryboard(AidStoryboard aidStoryboard)
    {
        aidStoryboard.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidStoryboard) ? 1 : 0;
    }

    /**
     * 批量删除分镜时间轴主
     *
     * @param ids 需要删除的分镜时间轴主主键
     * @return 结果
     */
    @Override
    public int deleteAidStoryboardByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除分镜时间轴主信息
     *
     * @param id 分镜时间轴主主键
     * @return 结果
     */
    @Override
    public int deleteAidStoryboardById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
