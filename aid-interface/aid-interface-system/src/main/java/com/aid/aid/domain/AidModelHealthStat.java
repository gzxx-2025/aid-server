package com.aid.aid.domain;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * AI模型健康统计对象 aid_model_health_stat
 *
 * <p>按「时间桶(30分钟) + 模型」聚合的运行状态计数表：只统计数字（成功/失败次数、耗时），
 * 不记录请求人、不记录请求明细；失败仅统计"上游返回错误"（提交被拒/生成失败/超时无响应），
 * 系统内部处理异常不计入。旧数据由定时任务归档为 txt 后从库中删除，表体量恒定很小。</p>
 *
 * @author 视觉AID
 */
@Data
@TableName("aid_model_health_stat")
public class AidModelHealthStat implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 统计时间桶起点（按30分钟对齐，如 2026-07-15 18:00:00 / 18:30:00） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date bucketTime;

    /** 服务商编码（aid_ai_provider.provider_code，冗余存储便于按供应商聚合查询） */
    private String providerCode;

    /** 模型编码（aid_ai_model.model_code） */
    private String modelCode;

    /** 媒体类型（TEXT/IMAGE/VIDEO/AUDIO） */
    private String mediaType;

    /** 成功次数（上游正常返回结果） */
    private Integer successCount;

    /** 失败次数（仅上游返回错误：提交被拒/生成失败/超时无响应） */
    private Integer failCount;

    /** 成功任务总耗时毫秒（平均耗时 = totalLatencyMs / successCount） */
    private Long totalLatencyMs;

    /** 本桶最近一次上游错误信息（截断至200字，供后台排查） */
    private String lastErrorMessage;

    /** 本桶最近一次上游错误时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastErrorTime;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
