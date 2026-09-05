package com.aid.auth.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 当前账号在线会话。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AccountSessionVO {

    private String sessionId;
    private boolean current;
    private String ipaddr;
    private String loginLocation;
    private String browser;
    private String os;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date activeTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date expireTime;
}
