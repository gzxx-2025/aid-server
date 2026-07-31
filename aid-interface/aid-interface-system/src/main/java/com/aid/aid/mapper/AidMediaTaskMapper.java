package com.aid.aid.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aid.aid.domain.media.AidMediaTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface AidMediaTaskMapper extends BaseMapper<AidMediaTask> {

    /**
     * 查询待补偿轮询的任务
     * @param status 目标状态（PROCESSING）
     * @param updateBefore 仅查询最近更新时间早于该时间的任务，避免扫到刚被前端轮询过的任务
     * @param maxRetry 最大重试次数阈值（用于控制补偿窗口）
     * @param limit 单次批量拉取上限，避免一次补偿过大
     * @return 待补偿任务列表（SQL 见 mapper/aid/AidMediaTaskMapper.xml）
     */
    List<AidMediaTask> selectTasksForCompensation(@Param("status") String status,
                                                  @Param("updateBefore") Date updateBefore,
                                                  @Param("maxRetry") Integer maxRetry,
                                                  @Param("limit") Integer limit);
}

