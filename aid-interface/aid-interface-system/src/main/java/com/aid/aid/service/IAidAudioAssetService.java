package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidAudioAsset;

/**
 * 音频资产 Service 接口
 *
 * @author 视觉AID
 */
public interface IAidAudioAssetService extends IService<AidAudioAsset>
{
    /**
     * 按主键查询音频资产
     *
     * @param id 主键
     * @return 音频资产；不存在返回 null
     */
    AidAudioAsset selectAidAudioAssetById(Long id);

    /**
     * 按条件查询音频资产列表（后台管理用）
     *
     * @param aidAudioAsset 查询条件（等值匹配）
     * @return 资产列表
     */
    List<AidAudioAsset> selectAidAudioAssetList(AidAudioAsset aidAudioAsset);

    /**
     * 新增音频资产
     *
     * @param aidAudioAsset 资产
     * @return 影响行数
     */
    int insertAidAudioAsset(AidAudioAsset aidAudioAsset);

    /**
     * 更新音频资产
     *
     * @param aidAudioAsset 资产
     * @return 影响行数
     */
    int updateAidAudioAsset(AidAudioAsset aidAudioAsset);

    /**
     * 批量软删除音频资产
     *
     * @param ids 主键集合
     * @return 影响行数
     */
    int deleteAidAudioAssetByIds(Long[] ids);

    /**
     * 按主键软删除音频资产
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteAidAudioAssetById(Long id);

    /**
     * 按 aid_audio_record.id 查询资产（唯一键）
     * 事件监听器在 OSS 就绪后会调用本接口做幂等判断：有则跳过写入，无则插入。
     *
     * @param audioRecordId 配音业务记录ID
     * @return 资产；不存在返回 null
     */
    AidAudioAsset selectByAudioRecordId(Long audioRecordId);
}
