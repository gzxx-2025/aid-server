package com.aid.aid.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aid.aid.domain.AidBalanceLog;
import com.aid.aid.domain.dto.BalanceConsumeAggDTO;

/**
 * 余额变动记录Mapper接口
 *
 * @author 视觉AID
 */
public interface AidBalanceLogMapper extends BaseMapper<AidBalanceLog>
{
    /**
     * 按业务（related_id）聚合查询某用户的积分消耗明细（供 C 端分页展示）。
     *
     * @param userId 用户ID
     * @return 聚合后的消耗明细列表（未分页时为全量，配合 PageHelper.startPage 分页）
     */
    @Select("""
            SELECT
                related_id AS relatedId,
                MAX(id) AS lastId,
                MAX(create_time) AS createTime,
                SUM(after_balance - before_balance) AS changeAmount,
                SUM(CASE WHEN change_type = 'freeze' THEN -amount ELSE 0 END) AS frozenAmount,
                SUM(CASE WHEN change_type IN ('unfreeze','settle_refund','settle_unfreeze') THEN amount ELSE 0 END) AS refundAmount,
                SUM(CASE WHEN change_type = 'settle_extra' THEN -amount ELSE 0 END) AS extraAmount,
                SUBSTRING_INDEX(GROUP_CONCAT(biz_type ORDER BY id SEPARATOR 0x1f), 0x1f, 1) AS bizType,
                SUBSTRING_INDEX(GROUP_CONCAT(biz_name ORDER BY id SEPARATOR 0x1f), 0x1f, 1) AS bizName,
                MAX(NULLIF(model_code, '')) AS modelCode
            FROM aid_balance_log
            WHERE user_id = #{userId}
                AND del_flag = '0'
                AND related_id IS NOT NULL
                AND related_id <> ''
                AND change_type NOT IN ('recharge', 'reward')
            GROUP BY related_id
            ORDER BY lastId DESC
            """)
    List<BalanceConsumeAggDTO> selectUserConsumeAgg(@Param("userId") Long userId);
}
