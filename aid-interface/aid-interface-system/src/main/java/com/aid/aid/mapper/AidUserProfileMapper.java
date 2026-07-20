package com.aid.aid.mapper;

import java.util.List;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aid.aid.domain.AidUserProfile;
import com.aid.aid.domain.vo.AidUserProfileVo;

/**
 * 用户扩展信息Mapper接口
 *
 * @author 视觉AID
 */
public interface AidUserProfileMapper extends BaseMapper<AidUserProfile>
{
    /**
     * 关联 sys_user 查询用户扩展信息列表（后台用户管理列表/详情）。
     * 仅返回主键与必要展示字段；昵称/手机号/状态来自 sys_user 联表。
     *
     * @param query 查询条件（userId/会员等级/实名/昵称/手机号/账号/状态）
     * @return 聚合用户信息列表
     */
    List<AidUserProfileVo> selectUserProfileVoList(AidUserProfileVo query);

    /**
     * 原子扣减余额(数据库层面保证不超扣)
     *
     * @param userId 用户ID
     * @param amount 扣减金额
     * @return 影响行数(0表示余额不足)
     */
    int deductBalance(@org.apache.ibatis.annotations.Param("userId") Long userId,
                      @org.apache.ibatis.annotations.Param("amount") java.math.BigDecimal amount);

    /**
     * 修复 AC1：条件 delta 更新（所有参数均为 delta，正数加负数减；null 表示不改该字段）。
     *
     * @param userId          用户ID
     * @param balanceDelta    balance 的增减量（null 不改）
     * @param frozenDelta     frozen_balance 的增减量（null 不改）
     * @param consumptionDelta total_consumption 的增减量（null 不改）
     * @param rechargeDelta   total_recharge 的增减量（null 不改）
     * @param balanceMin      前置条件：balance &ge; balanceMin（null 不判）
     * @param frozenMin       前置条件：frozen_balance &ge; frozenMin（null 不判）
     * @param consumptionMin  前置条件：total_consumption &ge; consumptionMin（null 不判）
     * @return 受影响行数（0 表示条件不满足，需要重试或报错）
     */
    int casDeltaUpdate(@org.apache.ibatis.annotations.Param("userId") Long userId,
                       @org.apache.ibatis.annotations.Param("balanceDelta") java.math.BigDecimal balanceDelta,
                       @org.apache.ibatis.annotations.Param("frozenDelta") java.math.BigDecimal frozenDelta,
                       @org.apache.ibatis.annotations.Param("consumptionDelta") java.math.BigDecimal consumptionDelta,
                       @org.apache.ibatis.annotations.Param("rechargeDelta") java.math.BigDecimal rechargeDelta,
                       @org.apache.ibatis.annotations.Param("balanceMin") java.math.BigDecimal balanceMin,
                       @org.apache.ibatis.annotations.Param("frozenMin") java.math.BigDecimal frozenMin,
                       @org.apache.ibatis.annotations.Param("consumptionMin") java.math.BigDecimal consumptionMin);
}
