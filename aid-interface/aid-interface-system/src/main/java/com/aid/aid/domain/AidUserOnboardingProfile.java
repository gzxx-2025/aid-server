package com.aid.aid.domain;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 用户引导全局配置对象 aid_user_onboarding_profile
 *
 * @author 视觉AID
 */
@Data
@TableName(value = "aid_user_onboarding_profile")
public class AidUserOnboardingProfile implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID，关联 sys_user */
    private Long userId;

    /** 是否全局关闭引导：0否 1是 */
    private Integer globalDismissed;

    /** 最后一次设置global_dismissed=true的时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date dismissedAt;

    /** 协议版本，与前端 schemaVersion 对齐 */
    private Integer schemaVersion;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 创建者 */
    private String createBy;

    /** 更新者 */
    private String updateBy;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;

    /** 备注 */
    private String remark;
}
