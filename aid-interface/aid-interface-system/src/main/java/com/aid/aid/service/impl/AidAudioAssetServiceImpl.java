package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidAudioAsset;
import com.aid.aid.mapper.AidAudioAssetMapper;
import com.aid.aid.service.IAidAudioAssetService;
import com.aid.common.utils.DateUtils;
import org.springframework.stereotype.Service;

/**
 * 音频资产 Service 实现
 *
 * @author 视觉AID
 */
@Service
public class AidAudioAssetServiceImpl extends ServiceImpl<AidAudioAssetMapper, AidAudioAsset>
        implements IAidAudioAssetService
{
    /** 未删除 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 已删除（软删） */
    private static final String DEL_FLAG_DELETED = "2";

    @Override
    public AidAudioAsset selectAidAudioAssetById(Long id)
    {
        if (Objects.isNull(id) || id <= 0)
        {
            return null;
        }
        return this.getById(id);
    }

    @Override
    public List<AidAudioAsset> selectAidAudioAssetList(AidAudioAsset aidAudioAsset)
    {
        // 后台管理使用；显式 .select 防止未来新增字段时漏查
        LambdaQueryWrapper<AidAudioAsset> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidAudioAsset::getId,
                AidAudioAsset::getUserId, AidAudioAsset::getProjectId,
                AidAudioAsset::getEpisodeId, AidAudioAsset::getStoryboardId,
                AidAudioAsset::getAudioRecordId, AidAudioAsset::getMediaTaskId,
                AidAudioAsset::getAudioUrl,
                AidAudioAsset::getFileSize, AidAudioAsset::getAudioFormat,
                AidAudioAsset::getSampleRate,
                AidAudioAsset::getTtsText, AidAudioAsset::getVoiceLibraryId,
                AidAudioAsset::getVoiceModelId, AidAudioAsset::getVoiceCode,
                AidAudioAsset::getVoiceName, AidAudioAsset::getEmotion,
                AidAudioAsset::getSpeechRate, AidAudioAsset::getLoudnessRate,
                AidAudioAsset::getPitch,
                AidAudioAsset::getAudioSource, AidAudioAsset::getAssetTitle,
                AidAudioAsset::getRemark, AidAudioAsset::getDelFlag,
                AidAudioAsset::getCreateBy, AidAudioAsset::getCreateTime,
                AidAudioAsset::getUpdateBy, AidAudioAsset::getUpdateTime);
        if (Objects.nonNull(aidAudioAsset))
        {
            if (Objects.nonNull(aidAudioAsset.getUserId()))
            {
                wrapper.eq(AidAudioAsset::getUserId, aidAudioAsset.getUserId());
            }
            if (Objects.nonNull(aidAudioAsset.getProjectId()))
            {
                wrapper.eq(AidAudioAsset::getProjectId, aidAudioAsset.getProjectId());
            }
            if (Objects.nonNull(aidAudioAsset.getEpisodeId()))
            {
                wrapper.eq(AidAudioAsset::getEpisodeId, aidAudioAsset.getEpisodeId());
            }
            if (Objects.nonNull(aidAudioAsset.getStoryboardId()))
            {
                wrapper.eq(AidAudioAsset::getStoryboardId, aidAudioAsset.getStoryboardId());
            }
            if (Objects.nonNull(aidAudioAsset.getVoiceLibraryId()))
            {
                wrapper.eq(AidAudioAsset::getVoiceLibraryId, aidAudioAsset.getVoiceLibraryId());
            }
            if (StrUtil.isNotBlank(aidAudioAsset.getVoiceCode()))
            {
                wrapper.eq(AidAudioAsset::getVoiceCode, aidAudioAsset.getVoiceCode());
            }
            if (Objects.nonNull(aidAudioAsset.getAudioSource()))
            {
                wrapper.eq(AidAudioAsset::getAudioSource, aidAudioAsset.getAudioSource());
            }
            if (StrUtil.isNotBlank(aidAudioAsset.getDelFlag()))
            {
                wrapper.eq(AidAudioAsset::getDelFlag, aidAudioAsset.getDelFlag());
            }
            if (StrUtil.isNotBlank(aidAudioAsset.getAssetTitle()))
            {
                wrapper.like(AidAudioAsset::getAssetTitle, aidAudioAsset.getAssetTitle());
            }
        }
        wrapper.orderByDesc(AidAudioAsset::getId);
        return this.list(wrapper);
    }

    @Override
    public int insertAidAudioAsset(AidAudioAsset aidAudioAsset)
    {
        if (Objects.isNull(aidAudioAsset))
        {
            return 0;
        }
        // 创建审计兜底
        if (Objects.isNull(aidAudioAsset.getCreateTime()))
        {
            aidAudioAsset.setCreateTime(DateUtils.getNowDate());
        }
        if (StrUtil.isBlank(aidAudioAsset.getDelFlag()))
        {
            aidAudioAsset.setDelFlag(DEL_FLAG_NORMAL);
        }
        return this.save(aidAudioAsset) ? 1 : 0;
    }

    @Override
    public int updateAidAudioAsset(AidAudioAsset aidAudioAsset)
    {
        if (Objects.isNull(aidAudioAsset) || Objects.isNull(aidAudioAsset.getId()))
        {
            return 0;
        }
        aidAudioAsset.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidAudioAsset) ? 1 : 0;
    }

    @Override
    public int deleteAidAudioAssetByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        boolean ok = this.update(Wrappers.<AidAudioAsset>lambdaUpdate()
                .in(AidAudioAsset::getId, Arrays.asList(ids))
                .eq(AidAudioAsset::getDelFlag, DEL_FLAG_NORMAL)
                .set(AidAudioAsset::getDelFlag, DEL_FLAG_DELETED)
                .set(AidAudioAsset::getUpdateTime, DateUtils.getNowDate()));
        return ok ? 1 : 0;
    }

    @Override
    public int deleteAidAudioAssetById(Long id)
    {
        if (Objects.isNull(id) || id <= 0)
        {
            return 0;
        }
        AidAudioAsset update = new AidAudioAsset();
        update.setId(id);
        update.setDelFlag(DEL_FLAG_DELETED);
        update.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(update) ? 1 : 0;
    }

    @Override
    public AidAudioAsset selectByAudioRecordId(Long audioRecordId)
    {
        if (Objects.isNull(audioRecordId) || audioRecordId <= 0)
        {
            return null;
        }
        LambdaQueryWrapper<AidAudioAsset> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidAudioAsset::getId, AidAudioAsset::getAudioRecordId,
                AidAudioAsset::getAudioUrl, AidAudioAsset::getDelFlag);
        wrapper.eq(AidAudioAsset::getAudioRecordId, audioRecordId);
        wrapper.last("LIMIT 1");
        return this.getOne(wrapper, false);
    }
}
