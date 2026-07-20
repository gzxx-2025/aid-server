package com.aid.aid.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aid.aid.domain.AidErrorLog;
import org.apache.ibatis.annotations.Param;

/**
 * 上游错误样本日志 Mapper。
 *
 * @author 视觉AID
 */
public interface AidErrorLogMapper extends BaseMapper<AidErrorLog> {

    /**
     * 累加同 hash 错误样本计数。
     * 使用 ON DUPLICATE KEY UPDATE 实现 UPSERT：
     * 同 (provider_code, sample_hash) 已存在时累加 occurrence_count 并更新 last_seen，
     * 否则插入新记录（first_seen=last_seen=now, occurrence_count=1）。
     */
    int upsertSample(@Param("providerCode") String providerCode,
                     @Param("modelCode") String modelCode,
                     @Param("httpStatus") Integer httpStatus,
                     @Param("rawMessage") String rawMessage,
                     @Param("matchedRuleId") Long matchedRuleId,
                     @Param("matchedErrorCode") String matchedErrorCode,
                     @Param("sampleHash") String sampleHash,
                     @Param("taskId") String taskId);
}
