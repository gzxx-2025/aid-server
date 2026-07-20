package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidAudioRecord;

/**
 * 分镜配音业务记录 Service 接口
 *
 * @author 视觉AID
 */
public interface IAidAudioRecordService extends IService<AidAudioRecord>
{
    /**
     * 查询配音业务记录
     *
     * @param id 主键
     * @return 配音业务记录
     */
    AidAudioRecord selectAidAudioRecordById(Long id);

    /**
     * 查询配音业务记录列表
     *
     * @param aidAudioRecord 查询条件
     * @return 配音业务记录集合
     */
    List<AidAudioRecord> selectAidAudioRecordList(AidAudioRecord aidAudioRecord);

    /**
     * 新增配音业务记录
     *
     * @param aidAudioRecord 配音业务记录
     * @return 影响行数
     */
    int insertAidAudioRecord(AidAudioRecord aidAudioRecord);

    /**
     * 修改配音业务记录
     *
     * @param aidAudioRecord 配音业务记录
     * @return 影响行数
     */
    int updateAidAudioRecord(AidAudioRecord aidAudioRecord);

    /**
     * 批量删除配音业务记录
     *
     * @param ids 主键集合
     * @return 影响行数
     */
    int deleteAidAudioRecordByIds(Long[] ids);

    /**
     * 删除配音业务记录
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteAidAudioRecordById(Long id);
}
