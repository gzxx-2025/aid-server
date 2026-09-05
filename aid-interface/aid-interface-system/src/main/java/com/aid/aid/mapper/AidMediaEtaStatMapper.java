package com.aid.aid.mapper;

import com.aid.aid.domain.AidMediaEtaStat;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;

/** 媒体任务 ETA 直方图 Mapper。 */
@Mapper
public interface AidMediaEtaStatMapper extends BaseMapper<AidMediaEtaStat> {

    @Insert({
        "INSERT INTO aid_media_eta_stat (bucket_date, phase, profile_key, provider_key, model_code, media_type, workload_key,",
        "sample_count, total_duration_ms, max_duration_ms, bucket_1s, bucket_5s, bucket_15s, bucket_30s,",
        "bucket_60s, bucket_120s, bucket_300s, bucket_600s, bucket_1200s, bucket_2400s, bucket_4800s, bucket_inf, create_time, update_time)",
        "VALUES (#{bucketDate}, #{phase}, #{profileKey}, #{providerKey}, #{modelCode}, #{mediaType}, #{workloadKey},",
        "#{sampleCount}, #{totalDurationMs}, #{maxDurationMs}, #{bucket1s}, #{bucket5s}, #{bucket15s}, #{bucket30s},",
        "#{bucket60s}, #{bucket120s}, #{bucket300s}, #{bucket600s}, #{bucket1200s}, #{bucket2400s}, #{bucket4800s}, #{bucketInf}, #{createTime}, #{updateTime})",
        "ON DUPLICATE KEY UPDATE sample_count = sample_count + VALUES(sample_count),",
        "total_duration_ms = total_duration_ms + VALUES(total_duration_ms),",
        "max_duration_ms = GREATEST(max_duration_ms, VALUES(max_duration_ms)),",
        "bucket_1s = bucket_1s + VALUES(bucket_1s), bucket_5s = bucket_5s + VALUES(bucket_5s),",
        "bucket_15s = bucket_15s + VALUES(bucket_15s), bucket_30s = bucket_30s + VALUES(bucket_30s),",
        "bucket_60s = bucket_60s + VALUES(bucket_60s), bucket_120s = bucket_120s + VALUES(bucket_120s),",
        "bucket_300s = bucket_300s + VALUES(bucket_300s), bucket_600s = bucket_600s + VALUES(bucket_600s),",
        "bucket_1200s = bucket_1200s + VALUES(bucket_1200s), bucket_2400s = bucket_2400s + VALUES(bucket_2400s),",
        "bucket_4800s = bucket_4800s + VALUES(bucket_4800s), bucket_inf = bucket_inf + VALUES(bucket_inf),",
        "update_time = VALUES(update_time)"
    })
    int upsertSample(AidMediaEtaStat stat);

    @Select({
        "SELECT COALESCE(SUM(sample_count), 0) AS sampleCount, COALESCE(SUM(total_duration_ms), 0) AS totalDurationMs,",
        "COALESCE(MAX(max_duration_ms), 0) AS maxDurationMs, COALESCE(SUM(bucket_1s), 0) AS bucket1s,",
        "COALESCE(SUM(bucket_5s), 0) AS bucket5s, COALESCE(SUM(bucket_15s), 0) AS bucket15s,",
        "COALESCE(SUM(bucket_30s), 0) AS bucket30s, COALESCE(SUM(bucket_60s), 0) AS bucket60s,",
        "COALESCE(SUM(bucket_120s), 0) AS bucket120s, COALESCE(SUM(bucket_300s), 0) AS bucket300s,",
        "COALESCE(SUM(bucket_600s), 0) AS bucket600s, COALESCE(SUM(bucket_1200s), 0) AS bucket1200s,",
        "COALESCE(SUM(bucket_2400s), 0) AS bucket2400s, COALESCE(SUM(bucket_4800s), 0) AS bucket4800s,",
        "COALESCE(SUM(bucket_inf), 0) AS bucketInf",
        "FROM aid_media_eta_stat WHERE bucket_date >= #{since} AND phase = #{phase}",
        "AND profile_key = #{profileKey}"
    })
    AidMediaEtaStat selectExactAggregate(@Param("since") Date since,
                                         @Param("phase") String phase,
                                         @Param("profileKey") String profileKey);

    @Select({
        "SELECT COALESCE(SUM(sample_count), 0) AS sampleCount, COALESCE(SUM(total_duration_ms), 0) AS totalDurationMs,",
        "COALESCE(MAX(max_duration_ms), 0) AS maxDurationMs, COALESCE(SUM(bucket_1s), 0) AS bucket1s,",
        "COALESCE(SUM(bucket_5s), 0) AS bucket5s, COALESCE(SUM(bucket_15s), 0) AS bucket15s,",
        "COALESCE(SUM(bucket_30s), 0) AS bucket30s, COALESCE(SUM(bucket_60s), 0) AS bucket60s,",
        "COALESCE(SUM(bucket_120s), 0) AS bucket120s, COALESCE(SUM(bucket_300s), 0) AS bucket300s,",
        "COALESCE(SUM(bucket_600s), 0) AS bucket600s, COALESCE(SUM(bucket_1200s), 0) AS bucket1200s,",
        "COALESCE(SUM(bucket_2400s), 0) AS bucket2400s, COALESCE(SUM(bucket_4800s), 0) AS bucket4800s,",
        "COALESCE(SUM(bucket_inf), 0) AS bucketInf",
        "FROM aid_media_eta_stat WHERE bucket_date >= #{since} AND phase = #{phase}",
        "AND provider_key = #{providerKey} AND model_code = #{modelCode} AND media_type = #{mediaType}"
    })
    AidMediaEtaStat selectModelAggregate(@Param("since") Date since,
                                         @Param("phase") String phase,
                                         @Param("providerKey") String providerKey,
                                         @Param("modelCode") String modelCode,
                                         @Param("mediaType") String mediaType);

    @Select({
        "SELECT COALESCE(SUM(sample_count), 0) AS sampleCount, COALESCE(SUM(total_duration_ms), 0) AS totalDurationMs,",
        "COALESCE(MAX(max_duration_ms), 0) AS maxDurationMs, COALESCE(SUM(bucket_1s), 0) AS bucket1s,",
        "COALESCE(SUM(bucket_5s), 0) AS bucket5s, COALESCE(SUM(bucket_15s), 0) AS bucket15s,",
        "COALESCE(SUM(bucket_30s), 0) AS bucket30s, COALESCE(SUM(bucket_60s), 0) AS bucket60s,",
        "COALESCE(SUM(bucket_120s), 0) AS bucket120s, COALESCE(SUM(bucket_300s), 0) AS bucket300s,",
        "COALESCE(SUM(bucket_600s), 0) AS bucket600s, COALESCE(SUM(bucket_1200s), 0) AS bucket1200s,",
        "COALESCE(SUM(bucket_2400s), 0) AS bucket2400s, COALESCE(SUM(bucket_4800s), 0) AS bucket4800s,",
        "COALESCE(SUM(bucket_inf), 0) AS bucketInf",
        "FROM aid_media_eta_stat WHERE bucket_date >= #{since} AND phase = #{phase} AND media_type = #{mediaType}"
    })
    AidMediaEtaStat selectMediaAggregate(@Param("since") Date since,
                                         @Param("phase") String phase,
                                         @Param("mediaType") String mediaType);

    @Delete("DELETE FROM aid_media_eta_stat WHERE bucket_date < #{before}")
    int deleteBefore(@Param("before") Date before);
}
