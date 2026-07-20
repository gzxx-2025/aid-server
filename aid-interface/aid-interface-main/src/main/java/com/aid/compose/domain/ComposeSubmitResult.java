package com.aid.compose.domain;

import lombok.Data;

/**
 * 核心合成提交结果。
 * providerTaskId（MPS TaskId）在任务提交到上游后回填，提交时刻可能为空。
 *
 * @author 视觉AID
 */
@Data
public class ComposeSubmitResult {

    /** 本地统一任务ID（aid_media_task.id） */
    private Long mediaTaskId;

    /** MPS 上游任务ID（providerTaskId，待回填） */
    private String providerTaskId;

    /** 预估秒数 */
    private long estimatedSeconds;
}
