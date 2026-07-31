package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidAiVoiceTag;
import com.aid.aid.mapper.AidAiVoiceTagMapper;
import com.aid.aid.service.IAidAiVoiceTagService;
import com.aid.common.utils.DateUtils;
import org.springframework.stereotype.Service;

/**
 * AI音色标签字典 Service 实现（基础 CRUD）
 * 本类仅承载裸 CRUD，业务校验（唯一键冲突、tag_type 合法性、字段长度等）
 * 由 {@code aid-interface-main} 的业务 Service 负责。
 *
 * @author 视觉AID
 */
@Service
public class AidAiVoiceTagServiceImpl extends ServiceImpl<AidAiVoiceTagMapper, AidAiVoiceTag> implements IAidAiVoiceTagService
{
    /** 删除标志：存在 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 删除标志：已删除 */
    private static final String DEL_FLAG_DELETED = "2";

    @Override
    public AidAiVoiceTag selectAidAiVoiceTagById(Long id)
    {
        return this.getById(id);
    }

    @Override
    public List<AidAiVoiceTag> selectAidAiVoiceTagList(AidAiVoiceTag query)
    {
        LambdaQueryWrapper<AidAiVoiceTag> wrapper = Wrappers.lambdaQuery();
        if (query != null)
        {
            if (StrUtil.isNotBlank(query.getTagType()))
            {
                wrapper.eq(AidAiVoiceTag::getTagType, query.getTagType());
            }
            if (StrUtil.isNotBlank(query.getTagCode()))
            {
                wrapper.like(AidAiVoiceTag::getTagCode, query.getTagCode());
            }
            if (StrUtil.isNotBlank(query.getTagName()))
            {
                wrapper.like(AidAiVoiceTag::getTagName, query.getTagName());
            }
            if (StrUtil.isNotBlank(query.getStatus()))
            {
                wrapper.eq(AidAiVoiceTag::getStatus, query.getStatus());
            }
            if (StrUtil.isNotBlank(query.getDelFlag()))
            {
                wrapper.eq(AidAiVoiceTag::getDelFlag, query.getDelFlag());
            }
        }
        // 稳定排序：sort_order DESC, id DESC
        wrapper.orderByDesc(AidAiVoiceTag::getSortOrder).orderByDesc(AidAiVoiceTag::getId);
        return this.list(wrapper);
    }

    @Override
    public int insertAidAiVoiceTag(AidAiVoiceTag voiceTag)
    {
        voiceTag.setCreateTime(DateUtils.getNowDate());
        return this.save(voiceTag) ? 1 : 0;
    }

    @Override
    public int updateAidAiVoiceTag(AidAiVoiceTag voiceTag)
    {
        voiceTag.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(voiceTag) ? 1 : 0;
    }

    @Override
    public int deleteAidAiVoiceTagByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        boolean ok = this.update(Wrappers.<AidAiVoiceTag>lambdaUpdate()
                .in(AidAiVoiceTag::getId, Arrays.asList(ids))
                .eq(AidAiVoiceTag::getDelFlag, DEL_FLAG_NORMAL)
                .set(AidAiVoiceTag::getDelFlag, DEL_FLAG_DELETED)
                .set(AidAiVoiceTag::getUpdateTime, DateUtils.getNowDate()));
        return ok ? 1 : 0;
    }

    @Override
    public int deleteAidAiVoiceTagById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        AidAiVoiceTag update = new AidAiVoiceTag();
        update.setId(id);
        update.setDelFlag(DEL_FLAG_DELETED);
        update.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(update) ? 1 : 0;
    }
}
