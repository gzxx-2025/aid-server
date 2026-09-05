package com.aid.aid.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aid.aid.domain.media.AidMediaTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /**
     * 查询尚未写入供应商理论成本台账的成功任务。使用任务中的不可变 modelId 快照关联供应商，
     * 供启用模拟余额或把初始时间回溯到过去时渐进补齐历史成本。
     */
    @Select("SELECT t.id, t.status, t.model_name, t.billing_snapshot_json, t.terminal_time, t.update_time " +
            "FROM aid_media_task t " +
            "JOIN aid_ai_model m ON m.id = CAST(JSON_UNQUOTE(JSON_EXTRACT(" +
            "CASE WHEN JSON_VALID(t.billing_snapshot_json) THEN t.billing_snapshot_json ELSE '{}' END, '$.modelId')) AS UNSIGNED) " +
            "LEFT JOIN aid_provider_cost_ledger l ON l.task_id = t.id AND l.entry_type = 'COST' " +
            "WHERE m.provider_id = #{providerId} AND t.status = 'SUCCEEDED' " +
            "AND COALESCE(t.terminal_time, t.update_time) >= #{initialTime} " +
            "AND JSON_VALID(t.billing_snapshot_json) = 1 " +
            "AND l.id IS NULL ORDER BY t.id ASC LIMIT #{limit}")
    List<AidMediaTask> selectUnledgeredProviderCosts(@Param("providerId") Long providerId,
                                                     @Param("initialTime") Date initialTime,
                                                     @Param("limit") Integer limit);
}

