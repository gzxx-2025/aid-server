package com.aid.auth.domain.dto;

import lombok.Data;

/**
 * 当前用户登录记录查询请求。
 *
 * @author 视觉AID
 */
@Data
public class LoginHistoryRequest {

    /** 页码 */
    private Integer pageNum;

    /** 每页条数 */
    private Integer pageSize;
}
