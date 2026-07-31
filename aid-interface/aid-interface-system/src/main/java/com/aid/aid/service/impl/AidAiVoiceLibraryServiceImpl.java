package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.mapper.AidAiVoiceLibraryMapper;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.common.utils.DateUtils;
import org.springframework.stereotype.Service;

/**
 * AI音色库 Service 实现（基础 CRUD）
 * 本类仅承载裸 CRUD 与简单条件组装；字段校验、{@code model_id} 反查、
 * 标签字典命中校验等业务编排放在 {@code aid-interface-main} 的业务 Service。
 *
 * @author 视觉AID
 */
@Service
public class AidAiVoiceLibraryServiceImpl extends ServiceImpl<AidAiVoiceLibraryMapper, AidAiVoiceLibrary> implements IAidAiVoiceLibraryService
{
    /** 删除标志：存在 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 删除标志：已删除 */
    private static final String DEL_FLAG_DELETED = "2";

    @Override
    public AidAiVoiceLibrary selectAidAiVoiceLibraryById(Long id)
    {
        return this.getById(id);
    }

    @Override
    public List<AidAiVoiceLibrary> selectAidAiVoiceLibraryList(AidAiVoiceLibrary query)
    {
        LambdaQueryWrapper<AidAiVoiceLibrary> wrapper = Wrappers.lambdaQuery();
        if (query != null)
        {
            if (query.getProviderId() != null)
            {
                wrapper.eq(AidAiVoiceLibrary::getProviderId, query.getProviderId());
            }
            if (query.getModelId() != null)
            {
                wrapper.eq(AidAiVoiceLibrary::getModelId, query.getModelId());
            }
            if (StrUtil.isNotBlank(query.getLanguage()))
            {
                wrapper.eq(AidAiVoiceLibrary::getLanguage, query.getLanguage());
            }
            if (StrUtil.isNotBlank(query.getGender()))
            {
                wrapper.eq(AidAiVoiceLibrary::getGender, query.getGender());
            }
            if (StrUtil.isNotBlank(query.getAgeRange()))
            {
                wrapper.eq(AidAiVoiceLibrary::getAgeRange, query.getAgeRange());
            }
            if (StrUtil.isNotBlank(query.getStatus()))
            {
                wrapper.eq(AidAiVoiceLibrary::getStatus, query.getStatus());
            }
            if (StrUtil.isNotBlank(query.getDelFlag()))
            {
                wrapper.eq(AidAiVoiceLibrary::getDelFlag, query.getDelFlag());
            }
            if (StrUtil.isNotBlank(query.getVoiceName()))
            {
                wrapper.like(AidAiVoiceLibrary::getVoiceName, query.getVoiceName());
            }
            if (StrUtil.isNotBlank(query.getVoiceCode()))
            {
                wrapper.like(AidAiVoiceLibrary::getVoiceCode, query.getVoiceCode());
            }
        }
        // 稳定排序
        wrapper.orderByDesc(AidAiVoiceLibrary::getSortOrder).orderByDesc(AidAiVoiceLibrary::getId);
        return this.list(wrapper);
    }

    @Override
    public int insertAidAiVoiceLibrary(AidAiVoiceLibrary voiceLibrary)
    {
        voiceLibrary.setCreateTime(DateUtils.getNowDate());
        return this.save(voiceLibrary) ? 1 : 0;
    }

    @Override
    public int updateAidAiVoiceLibrary(AidAiVoiceLibrary voiceLibrary)
    {
        voiceLibrary.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(voiceLibrary) ? 1 : 0;
    }

    @Override
    public int deleteAidAiVoiceLibraryByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        boolean ok = this.update(Wrappers.<AidAiVoiceLibrary>lambdaUpdate()
                .in(AidAiVoiceLibrary::getId, Arrays.asList(ids))
                .eq(AidAiVoiceLibrary::getDelFlag, DEL_FLAG_NORMAL)
                .set(AidAiVoiceLibrary::getDelFlag, DEL_FLAG_DELETED)
                .set(AidAiVoiceLibrary::getUpdateTime, DateUtils.getNowDate()));
        return ok ? 1 : 0;
    }

    @Override
    public int deleteAidAiVoiceLibraryById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        AidAiVoiceLibrary update = new AidAiVoiceLibrary();
        update.setId(id);
        update.setDelFlag(DEL_FLAG_DELETED);
        update.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(update) ? 1 : 0;
    }
}
