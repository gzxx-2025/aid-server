package com.aid.aid.domain;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 用户引导 Tour 配置对象 aid_onboarding_tour_config
 *
 * @author 视觉AID
 */
@Data
@TableName(value = "aid_onboarding_tour_config")
public class AidOnboardingTourConfig implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** Tour 标识 */
    private String tourId;

    /** Tour 名称 */
    private String tourName;

    /** Tour 类型：overview=概览向导 / highlight=页面高亮 */
    private String tourType;

    /** Tour 说明 */
    private String description;

    /** 是否启用：0否 1是 */
    private Integer isEnabled;

    /** 排序 */
    private Integer sortOrder;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
