package com.aid.aid.domain;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 用户引导 Tour 进度对象 aid_user_onboarding_tour
 *
 * @author 视觉AID
 */
@Data
@TableName(value = "aid_user_onboarding_tour")
public class AidUserOnboardingTour implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** Tour 标识，来自 aid_onboarding_tour_config */
    private String tourId;

    /** 状态：completed/skipped/in_progress */
    private String status;

    /** 用户完成/跳过时该 Tour 的前端版本号 */
    private Integer tourVersion;

    /** 进行中时最后一步 stepId */
    private String lastStepId;

    /** 客户端上报时间（冲突合并依据） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date clientUpdatedAt;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
