package com.aid.aid.domain.vo;

import java.io.Serializable;

import com.aid.common.aid.oss.annotation.MediaUrl;

import lombok.Data;

/**
 * 发布管理用户搜索结果VO（按 邮箱/手机号/昵称 搜索，供白名单添加与权限设置）
 *
 * @author 视觉AID
 */
@Data
public class AidPublishUserVo implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long userId;

    /** 用户昵称（展示格式：昵称(ID)） */
    private String nickName;

    /** 用户邮箱 */
    private String email;

    /** 用户手机号 */
    private String phonenumber;

    /** 用户头像（完整URL） */
    @MediaUrl
    private String avatar;

    /** 用户级发布权限: 1允许 0禁止 */
    private Integer publishEnabled;

    /** 是否已在发布白名单 */
    private Boolean inWhitelist;
}
