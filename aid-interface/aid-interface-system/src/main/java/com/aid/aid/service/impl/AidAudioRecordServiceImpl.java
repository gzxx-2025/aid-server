package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import cn.hutool.core.util.StrUtil;
import com.aid.common.utils.DateUtils;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.mapper.AidAudioRecordMapper;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.service.IAidAudioRecordService;

/**
 * 分镜配音业务记录 Service 实现
 *
 * @author 视觉AID
 */
@Service
public class AidAudioRecordServiceImpl extends ServiceImpl<AidAudioRecordMapper, AidAudioRecord> implements IAidAudioRecordService
{
    @Override
    public AidAudioRecord selectAidAudioRecordById(Long id)
    {
        return this.getById(id);
    }

    @Override
    public List<AidAudioRecord> selectAidAudioRecordList(AidAudioRecord aidAudioRecord)
    {
        LambdaQueryWrapper<AidAudioRecord> wrapper = Wrappers.lambdaQuery();
        if (aidAudioRecord != null)
        {
            if (aidAudioRecord.getUserId() != null)
            {
                wrapper.eq(AidAudioRecord::getUserId, aidAudioRecord.getUserId());
            }
            if (aidAudioRecord.getProjectId() != null)
            {
                wrapper.eq(AidAudioRecord::getProjectId, aidAudioRecord.getProjectId());
            }
            if (aidAudioRecord.getEpisodeId() != null)
            {
                wrapper.eq(AidAudioRecord::getEpisodeId, aidAudioRecord.getEpisodeId());
            }
            if (aidAudioRecord.getStoryboardId() != null)
            {
                wrapper.eq(AidAudioRecord::getStoryboardId, aidAudioRecord.getStoryboardId());
            }
            if (aidAudioRecord.getAudioSource() != null)
            {
                wrapper.eq(AidAudioRecord::getAudioSource, aidAudioRecord.getAudioSource());
            }
            if (aidAudioRecord.getVoiceModelId() != null)
            {
                wrapper.eq(AidAudioRecord::getVoiceModelId, aidAudioRecord.getVoiceModelId());
            }
            if (aidAudioRecord.getVoiceLibraryId() != null)
            {
                wrapper.eq(AidAudioRecord::getVoiceLibraryId, aidAudioRecord.getVoiceLibraryId());
            }
            if (StrUtil.isNotBlank(aidAudioRecord.getTimbreCode()))
            {
                wrapper.eq(AidAudioRecord::getTimbreCode, aidAudioRecord.getTimbreCode());
            }
            if (aidAudioRecord.getEnableLipSync() != null)
            {
                wrapper.eq(AidAudioRecord::getEnableLipSync, aidAudioRecord.getEnableLipSync());
            }
            if (StrUtil.isNotBlank(aidAudioRecord.getStatus()))
            {
                wrapper.eq(AidAudioRecord::getStatus, aidAudioRecord.getStatus());
            }
        }
        wrapper.orderByDesc(AidAudioRecord::getId);
        return this.list(wrapper);
    }

    @Override
    public int insertAidAudioRecord(AidAudioRecord aidAudioRecord)
    {
        aidAudioRecord.setCreateTime(DateUtils.getNowDate());
        return this.save(aidAudioRecord) ? 1 : 0;
    }

    @Override
    public int updateAidAudioRecord(AidAudioRecord aidAudioRecord)
    {
        aidAudioRecord.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidAudioRecord) ? 1 : 0;
    }

    @Override
    public int deleteAidAudioRecordByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    @Override
    public int deleteAidAudioRecordById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
