package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidAiVoiceTag;

/**
 * AI音色标签字典 Service 接口
 *
 * @author 视觉AID
 */
public interface IAidAiVoiceTagService extends IService<AidAiVoiceTag>
{
    /**
     * 查询单条音色标签
     */
    AidAiVoiceTag selectAidAiVoiceTagById(Long id);

    /**
     * 查询音色标签列表
     */
    List<AidAiVoiceTag> selectAidAiVoiceTagList(AidAiVoiceTag query);

    /**
     * 新增音色标签
     */
    int insertAidAiVoiceTag(AidAiVoiceTag voiceTag);

    /**
     * 修改音色标签
     */
    int updateAidAiVoiceTag(AidAiVoiceTag voiceTag);

    /**
     * 批量软删除音色标签
     */
    int deleteAidAiVoiceTagByIds(Long[] ids);

    /**
     * 单条软删除音色标签
     */
    int deleteAidAiVoiceTagById(Long id);
}
