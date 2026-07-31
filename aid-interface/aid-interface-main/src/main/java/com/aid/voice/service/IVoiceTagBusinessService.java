package com.aid.voice.service;

import java.util.List;
import com.aid.aid.domain.AidAiVoiceTag;
import com.aid.voice.dto.VoiceTagListRequest;
import com.aid.voice.dto.VoiceTagUpsertRequest;

/**
 * 音色标签字典业务 Service 接口
 *
 * @author 视觉AID
 */
public interface IVoiceTagBusinessService
{
    /**
     * 分页查询音色标签（后台管理用）
     */
    List<AidAiVoiceTag> listVoiceTags(VoiceTagListRequest request);

    /**
     * 单条详情
     */
    AidAiVoiceTag getVoiceTagDetail(Long id);

    /**
     * 新增标签；校验 tag_type / 唯一键 / 字段长度
     *
     * @return 新建的主键
     */
    Long createVoiceTag(VoiceTagUpsertRequest request);

    /**
     * 更新标签
     */
    void updateVoiceTag(VoiceTagUpsertRequest request);

    /**
     * 批量软删除
     */
    void deleteVoiceTags(Long[] ids);

    /**
     * 按 tag_type 查出所有启用且未删除的标签（给业务校验与 C 端打包复用）
     */
    List<AidAiVoiceTag> listActiveTagsByType(String tagType);

    /**
     * 校验传入的 tag_code 集合是否在对应 tag_type 的活跃字典中命中
     *
     * @return 未命中的 tag_code 列表（为空表示全部命中）
     */
    List<String> findMissingTagCodes(String tagType, List<String> tagCodes);
}
