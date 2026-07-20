package com.aid.compose.service;

import com.aid.compose.domain.ComposeCommand;
import com.aid.compose.domain.ComposeSubmitResult;
import com.aid.compose.domain.ComposeTracks;

/**
 * 核心合成方法（URL 驱动、无状态、可复用）。
 * 把分组素材数据组装为 MPS EditMedia 请求并提交统一任务。接口①②共用，不依赖任何业务表。
 *
 * @author 视觉AID
 */
public interface CoreComposeService {

    /**
     * 核心合成：URL 驱动，无状态。组装四轨道 → MPS EditMedia → 落 COMPOSE 任务（含预冻结）。
     *
     * @param command 合成指令（已归一化的分组 URL 数据 + 业务回填目标）
     * @return 合成提交结果（本地 mediaTaskId + MPS providerTaskId 待回填）
     */
    ComposeSubmitResult compose(ComposeCommand command);

    /**
     * 四轨道生成算法（纯函数，便于属性测试覆盖）：
     * 视频轨连续无洞；缺配音补等长 Empty；字幕绝对钉位；BGM global/分组动态。
     *
     * @param command 合成指令
     * @return 四轨道组装结果
     */
    ComposeTracks buildTracks(ComposeCommand command);
}
