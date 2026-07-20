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
import com.aid.aid.mapper.AidGenRecordMapper;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.service.IAidGenRecordService;

/**
 * AI生图/生视频抽卡记录Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidGenRecordServiceImpl extends ServiceImpl<AidGenRecordMapper, AidGenRecord> implements IAidGenRecordService
{
    @Autowired
    private AidGenRecordMapper aidGenRecordMapper;

    /**
     * 查询AI生图/生视频抽卡记录
     *
     * @param id AI生图/生视频抽卡记录主键
     * @return AI生图/生视频抽卡记录
     */
    @Override
    public AidGenRecord selectAidGenRecordById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询AI生图/生视频抽卡记录列表
     *
     * @param aidGenRecord AI生图/生视频抽卡记录
     * @return AI生图/生视频抽卡记录
     */
    @Override
    public List<AidGenRecord> selectAidGenRecordList(AidGenRecord aidGenRecord)
    {
        LambdaQueryWrapper<AidGenRecord> wrapper = Wrappers.lambdaQuery();
        if (aidGenRecord != null)
        {
            if (aidGenRecord.getUserId() != null)
            {
                wrapper.eq(AidGenRecord::getUserId, aidGenRecord.getUserId());
            }
            if (aidGenRecord.getProjectId() != null)
            {
                wrapper.eq(AidGenRecord::getProjectId, aidGenRecord.getProjectId());
            }
            if (aidGenRecord.getEpisodeId() != null)
            {
                wrapper.eq(AidGenRecord::getEpisodeId, aidGenRecord.getEpisodeId());
            }
            if (aidGenRecord.getStoryboardId() != null)
            {
                wrapper.eq(AidGenRecord::getStoryboardId, aidGenRecord.getStoryboardId());
            }
            if (StrUtil.isNotBlank(aidGenRecord.getGenType()))
            {
                wrapper.eq(AidGenRecord::getGenType, aidGenRecord.getGenType());
            }
            if (StrUtil.isNotBlank(aidGenRecord.getTaskId()))
            {
                wrapper.eq(AidGenRecord::getTaskId, aidGenRecord.getTaskId());
            }
            if (aidGenRecord.getStatus() != null)
            {
                wrapper.eq(AidGenRecord::getStatus, aidGenRecord.getStatus());
            }
            if (aidGenRecord.getModelId() != null)
            {
                wrapper.eq(AidGenRecord::getModelId, aidGenRecord.getModelId());
            }
            if (aidGenRecord.getIsSelected() != null)
            {
                wrapper.eq(AidGenRecord::getIsSelected, aidGenRecord.getIsSelected());
            }
        }
        wrapper.orderByDesc(AidGenRecord::getId);
        return this.list(wrapper);
    }

    /**
     * 新增AI生图/生视频抽卡记录
     *
     * @param aidGenRecord AI生图/生视频抽卡记录
     * @return 结果
     */
    @Override
    public int insertAidGenRecord(AidGenRecord aidGenRecord)
    {
        aidGenRecord.setCreateTime(DateUtils.getNowDate());
        return this.save(aidGenRecord) ? 1 : 0;
    }

    /**
     * 修改AI生图/生视频抽卡记录
     *
     * @param aidGenRecord AI生图/生视频抽卡记录
     * @return 结果
     */
    @Override
    public int updateAidGenRecord(AidGenRecord aidGenRecord)
    {
        aidGenRecord.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidGenRecord) ? 1 : 0;
    }

    /**
     * 批量删除AI生图/生视频抽卡记录
     *
     * @param ids 需要删除的AI生图/生视频抽卡记录主键
     * @return 结果
     */
    @Override
    public int deleteAidGenRecordByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除AI生图/生视频抽卡记录信息
     *
     * @param id AI生图/生视频抽卡记录主键
     * @return 结果
     */
    @Override
    public int deleteAidGenRecordById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
