package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidBalanceLog;
import com.aid.aid.domain.dto.BalanceConsumeAggDTO;

/**
 * 余额变动记录Service接口
 *
 * @author 视觉AID
 */
public interface IAidBalanceLogService extends IService<AidBalanceLog>
{
    /**
     * 按业务（related_id）聚合查询某用户的积分消耗明细。
     * 同一任务的预冻结/结算/退款/补扣多行聚合为一条；排除充值。
     * 需配合 {@code PageHelper.startPage} 在调用前开启分页。
     *
     * @param userId 用户ID
     * @return 聚合后的消耗明细列表（最新业务在前）
     */
    List<BalanceConsumeAggDTO> selectUserConsumeAgg(Long userId);

    /**
     * 查询余额变动记录
     *
     * @param id 余额变动记录主键
     * @return 余额变动记录
     */
    public AidBalanceLog selectAidBalanceLogById(Long id);

    /**
     * 查询余额变动记录列表
     *
     * @param aidBalanceLog 余额变动记录
     * @return 余额变动记录集合
     */
    public List<AidBalanceLog> selectAidBalanceLogList(AidBalanceLog aidBalanceLog);

    /**
     * 新增余额变动记录
     *
     * @param aidBalanceLog 余额变动记录
     * @return 结果
     */
    public int insertAidBalanceLog(AidBalanceLog aidBalanceLog);

    /**
     * 修改余额变动记录
     *
     * @param aidBalanceLog 余额变动记录
     * @return 结果
     */
    public int updateAidBalanceLog(AidBalanceLog aidBalanceLog);

    /**
     * 批量删除余额变动记录
     *
     * @param ids 需要删除的余额变动记录主键集合
     * @return 结果
     */
    public int deleteAidBalanceLogByIds(Long[] ids);

    /**
     * 删除余额变动记录信息
     *
     * @param id 余额变动记录主键
     * @return 结果
     */
    public int deleteAidBalanceLogById(Long id);
}
