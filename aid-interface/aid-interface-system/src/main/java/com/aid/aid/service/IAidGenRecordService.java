package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidGenRecord;

/**
 * AI生图/生视频抽卡记录Service接口
 *
 * @author 视觉AID
 */
public interface IAidGenRecordService extends IService<AidGenRecord>
{
    /**
     * 查询AI生图/生视频抽卡记录
     *
     * @param id AI生图/生视频抽卡记录主键
     * @return AI生图/生视频抽卡记录
     */
    public AidGenRecord selectAidGenRecordById(Long id);

    /**
     * 查询AI生图/生视频抽卡记录列表
     *
     * @param aidGenRecord AI生图/生视频抽卡记录
     * @return AI生图/生视频抽卡记录集合
     */
    public List<AidGenRecord> selectAidGenRecordList(AidGenRecord aidGenRecord);

    /**
     * 新增AI生图/生视频抽卡记录
     *
     * @param aidGenRecord AI生图/生视频抽卡记录
     * @return 结果
     */
    public int insertAidGenRecord(AidGenRecord aidGenRecord);

    /**
     * 修改AI生图/生视频抽卡记录
     *
     * @param aidGenRecord AI生图/生视频抽卡记录
     * @return 结果
     */
    public int updateAidGenRecord(AidGenRecord aidGenRecord);

    /**
     * 批量删除AI生图/生视频抽卡记录
     *
     * @param ids 需要删除的AI生图/生视频抽卡记录主键集合
     * @return 结果
     */
    public int deleteAidGenRecordByIds(Long[] ids);

    /**
     * 删除AI生图/生视频抽卡记录信息
     *
     * @param id AI生图/生视频抽卡记录主键
     * @return 结果
     */
    public int deleteAidGenRecordById(Long id);
}
