package com.aid.aid.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 媒体任务 ETA 聚合直方图。每行只保存一个日期、阶段和任务画像的聚合数字。
 */
@Data
@TableName("aid_media_eta_stat")
public class AidMediaEtaStat implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Date bucketDate;
    private String phase;
    private String profileKey;
    private String providerKey;
    private String modelCode;
    private String mediaType;
    private String workloadKey;
    private Long sampleCount;
    private Long totalDurationMs;
    private Long maxDurationMs;
    private Long bucket1s;
    private Long bucket5s;
    private Long bucket15s;
    private Long bucket30s;
    private Long bucket60s;
    private Long bucket120s;
    private Long bucket300s;
    private Long bucket600s;
    private Long bucket1200s;
    private Long bucket2400s;
    private Long bucket4800s;
    private Long bucketInf;
    private Date createTime;
    private Date updateTime;
}
