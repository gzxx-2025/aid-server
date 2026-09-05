package com.aid.auth.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 当前账号登录记录分页结果。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AccountLoginHistoryPageVO {

    private long total;
    private int pageNum;
    private int pageSize;
    private List<AccountLoginHistoryVO> list;
}
