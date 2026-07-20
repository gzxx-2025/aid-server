package com.aid.system.domain;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 在线用户（按用户ID聚合，一个用户下可包含多个在线会话/Token）
 *
 * @author 视觉AID
 */
@Data
public class SysOnlineUser
{
    /** 用户ID */
    private Long userId;

    /** 用户名称 */
    private String userName;

    /** 部门名称 */
    private String deptName;

    /** 该用户当前在线会话（Token）数量 */
    private Integer onlineCount;

    /** 该用户最近一次登录时间 */
    private Long lastLoginTime;

    /** 该用户名下所有在线会话（Token）明细 */
    private List<SysUserOnline> tokens = new ArrayList<>();
}
