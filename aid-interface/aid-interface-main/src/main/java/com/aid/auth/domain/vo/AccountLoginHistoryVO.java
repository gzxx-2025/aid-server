package com.aid.auth.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 当前账号登录记录。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AccountLoginHistoryVO {

    private Long id;
    private String status;
    private String ipaddr;
    private String loginLocation;
    private String browser;
    private String os;
    private String message;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date loginTime;
}
