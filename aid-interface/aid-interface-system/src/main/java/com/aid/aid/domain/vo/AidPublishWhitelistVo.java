package com.aid.aid.domain.vo;

import java.io.Serializable;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;

import lombok.Data;

/**
 * 发布白名单列表行VO（白名单 联表 sys_user 用户信息）
 *
 * @author 视觉AID
 */
@Data
public class AidPublishWhitelistVo implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 白名单记录ID */
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 用户昵称 */
    private String nickName;

    /** 用户邮箱 */
    private String email;

    /** 用户手机号 */
    private String phonenumber;

    /** 用户头像（完整URL） */
    @MediaUrl
    private String avatar;

    /** 备注（加入原因等） */
    private String remark;

    /** 添加人 */
    private String createBy;

    /** 添加时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
