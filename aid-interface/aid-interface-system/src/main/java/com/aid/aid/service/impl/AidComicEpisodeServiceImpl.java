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
import com.aid.aid.mapper.AidComicEpisodeMapper;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.service.IAidComicEpisodeService;

/**
 * 剧集信息Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidComicEpisodeServiceImpl extends ServiceImpl<AidComicEpisodeMapper, AidComicEpisode> implements IAidComicEpisodeService
{
    @Autowired
    private AidComicEpisodeMapper aidComicEpisodeMapper;

    /**
     * 查询剧集信息
     *
     * @param id 剧集信息主键
     * @return 剧集信息
     */
    @Override
    public AidComicEpisode selectAidComicEpisodeById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询剧集信息列表
     *
     * @param aidComicEpisode 剧集信息
     * @return 剧集信息
     */
    @Override
    public List<AidComicEpisode> selectAidComicEpisodeList(AidComicEpisode aidComicEpisode)
    {
        LambdaQueryWrapper<AidComicEpisode> wrapper = Wrappers.lambdaQuery();
        if (aidComicEpisode != null)
        {
            if (aidComicEpisode.getProjectId() != null)
            {
                wrapper.eq(AidComicEpisode::getProjectId, aidComicEpisode.getProjectId());
            }
            if (aidComicEpisode.getEpisodeNo() != null)
            {
                wrapper.eq(AidComicEpisode::getEpisodeNo, aidComicEpisode.getEpisodeNo());
            }
            if (StrUtil.isNotBlank(aidComicEpisode.getComicTitle()))
            {
                wrapper.like(AidComicEpisode::getComicTitle, aidComicEpisode.getComicTitle());
            }
            if (aidComicEpisode.getUserId() != null)
            {
                wrapper.eq(AidComicEpisode::getUserId, aidComicEpisode.getUserId());
            }
            if (StrUtil.isNotBlank(aidComicEpisode.getGenMode()))
            {
                wrapper.eq(AidComicEpisode::getGenMode, aidComicEpisode.getGenMode());
            }
            if (StrUtil.isNotBlank(aidComicEpisode.getCreationMode()))
            {
                wrapper.eq(AidComicEpisode::getCreationMode, aidComicEpisode.getCreationMode());
            }
            if (aidComicEpisode.getCurrentStep() != null)
            {
                wrapper.eq(AidComicEpisode::getCurrentStep, aidComicEpisode.getCurrentStep());
            }
            if (aidComicEpisode.getStatus() != null)
            {
                wrapper.eq(AidComicEpisode::getStatus, aidComicEpisode.getStatus());
            }
        }
        wrapper.orderByAsc(AidComicEpisode::getProjectId).orderByAsc(AidComicEpisode::getEpisodeNo);
        return this.list(wrapper);
    }

    /**
     * 新增剧集信息
     *
     * @param aidComicEpisode 剧集信息
     * @return 结果
     */
    @Override
    public int insertAidComicEpisode(AidComicEpisode aidComicEpisode)
    {
        aidComicEpisode.setCreateTime(DateUtils.getNowDate());
        return this.save(aidComicEpisode) ? 1 : 0;
    }

    /**
     * 修改剧集信息
     *
     * @param aidComicEpisode 剧集信息
     * @return 结果
     */
    @Override
    public int updateAidComicEpisode(AidComicEpisode aidComicEpisode)
    {
        aidComicEpisode.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidComicEpisode) ? 1 : 0;
    }

    /**
     * 批量删除剧集信息
     *
     * @param ids 需要删除的剧集信息主键
     * @return 结果
     */
    @Override
    public int deleteAidComicEpisodeByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除剧集信息信息
     *
     * @param id 剧集信息主键
     * @return 结果
     */
    @Override
    public int deleteAidComicEpisodeById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
