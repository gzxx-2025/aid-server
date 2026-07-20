package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidAiVoiceLibrary;

/**
 * AI音色库 Service 接口（基础 CRUD）
 *
 * @author 视觉AID
 */
public interface IAidAiVoiceLibraryService extends IService<AidAiVoiceLibrary>
{
    /**
     * 查询单条音色
     */
    AidAiVoiceLibrary selectAidAiVoiceLibraryById(Long id);

    /**
     * 查询音色列表（后台管理用基础查询，由业务层拼装过滤条件）
     */
    List<AidAiVoiceLibrary> selectAidAiVoiceLibraryList(AidAiVoiceLibrary query);

    /**
     * 新增音色
     */
    int insertAidAiVoiceLibrary(AidAiVoiceLibrary voiceLibrary);

    /**
     * 修改音色
     */
    int updateAidAiVoiceLibrary(AidAiVoiceLibrary voiceLibrary);

    /**
     * 批量软删除音色
     */
    int deleteAidAiVoiceLibraryByIds(Long[] ids);

    /**
     * 单条软删除音色
     */
    int deleteAidAiVoiceLibraryById(Long id);
}
