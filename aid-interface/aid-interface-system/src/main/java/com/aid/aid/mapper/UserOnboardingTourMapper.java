package com.aid.aid.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aid.aid.domain.AidUserOnboardingTour;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户引导 Tour 进度 Mapper 接口
 *
 * @author 视觉AID
 */
public interface UserOnboardingTourMapper extends BaseMapper<AidUserOnboardingTour>
{
    /**
     * 带行锁查询（用于冲突合并前的 SELECT）
     *
     * @param userId 用户ID
     * @param tourId Tour 标识
     * @return Tour 进度记录，无则 null
     */
    @Select("SELECT * FROM aid_user_onboarding_tour WHERE user_id = #{userId} AND tour_id = #{tourId} FOR UPDATE")
    AidUserOnboardingTour selectByUserIdAndTourIdForUpdate(@Param("userId") Long userId,
                                                            @Param("tourId") String tourId);
}
