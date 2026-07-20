package com.aid.quartz.domain;

import lombok.Data;

/**
 * 数据库承载压力快照（取自 Druid 主数据源连接池），用于动态计算定时任务推荐频率
 *
 * @author AID
 */
@Data
public class SysJobDbPressure
{
    /** 压力评分（0~1，越大压力越高） */
    private Double score;

    /** 压力级别（LOW/MEDIUM/HIGH） */
    private String level;

    /** 活跃连接数 */
    private Integer activeCount;

    /** 连接池最大连接数 */
    private Integer maxActive;

    /** 空闲连接数 */
    private Integer poolingCount;

    /** 等待获取连接的线程数 */
    private Integer waitThreadCount;

    /** 连接池使用率（百分比，0~100） */
    private Double poolUsagePercent;

    /** 指标是否采集成功（失败时按低压力兜底） */
    private Boolean available;
}
