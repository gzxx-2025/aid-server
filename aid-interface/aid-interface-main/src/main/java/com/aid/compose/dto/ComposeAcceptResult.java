package com.aid.compose.dto;

import lombok.Data;

import java.util.List;

/**
 * 接口1 出参：受理结果（异步进行）。
 *
 * @author 视觉AID
 */
@Data
public class ComposeAcceptResult {

    /** 本批合成批次号(聚合配音事件用) */
    private String composeBatchId;

    /** 已发起的配音记录ID(前端可轮询) */
    private List<Long> audioRecordIds;

    /** 受理状态：ACCEPTED(受理成功，异步进行) */
    private String status;
}
