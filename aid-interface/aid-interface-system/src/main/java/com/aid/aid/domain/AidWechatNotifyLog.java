package com.aid.aid.domain;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.core.domain.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 微信模板消息推送日志对象 aid_wechat_notify_log
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("aid_wechat_notify_log")
public class AidWechatNotifyLog extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 微信公众号 OpenID */
    private String openid;

    /** 推送事件类型 */
    private String eventType;

    /** 业务类型 */
    private String bizType;

    /** 业务ID */
    private Long bizId;

    /** 任务ID */
    private Long taskId;

    /** 发送状态 SUCCESS/FAILED/SKIPPED */
    private String status;

    /** 模板ID */
    private String templateId;

    /** 微信防重ID */
    private String clientMsgId;

    /** 请求JSON */
    private String requestJson;

    /** 响应JSON */
    private String responseJson;

    /** 微信错误码 */
    private Integer errcode;

    /** 微信错误描述 */
    private String errmsg;

    /** 发送时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date sendTime;

    /** 删除标志 */
    private String delFlag;
}
