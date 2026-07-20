package com.aid.compose.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.compose.dto.ComposeAcceptResult;
import com.aid.compose.dto.ComposeStatusRequest;
import com.aid.compose.dto.ComposeStatusResult;
import com.aid.compose.dto.StoryboardComposeRequest;
import com.aid.compose.service.VideoComposeService;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 接口1：分镜一键配音（C 端，纯配音合成）。
 * 接收分镜ID列表与配音参数，先对每条分镜异步发起配音，配音齐全后由事件链自动触发合成
 * （仅分镜视频 + 配音，不烧字幕、不加背景音乐——字幕/BGM 由成片合成导出阶段处理）。
 * 本接口为受理型异步接口：同步返回合成批次号与已发起的配音记录ID，前端可据此轮询进度。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/compose")
public class ComposeController extends BaseController {

    /** 视频合成业务编排服务 */
    @Resource
    private VideoComposeService videoComposeService;

    /**
     * 分镜一键配音（纯配音合成，不烧字幕、不加背景音乐）。
     * URL：POST /api/user/compose/voiceover
     * 用户身份由登录态解析（Service 内取 SecurityUtils），请求体不携带 userId。
     *
     * @param request 接口1 入参（分镜ID列表 + 配音参数 + 可选分辨率）
     * @return 受理结果（composeBatchId + 配音记录ID列表 + status=ACCEPTED）
     */
    @PostMapping("/voiceover")
    public AjaxResult voiceover(@RequestBody StoryboardComposeRequest request) {
        ComposeAcceptResult result = videoComposeService.composeWithVoiceover(request);
        return success(result);
    }

    /**
     * 合成进度查询（纯轮询）。
     *
     * @param request 入参（composeBatchId 必填）
     * @return 合成进度（配音进度 + 合成状态 + 成片地址）
     */
    @PostMapping("/status")
    public AjaxResult status(@Valid @RequestBody ComposeStatusRequest request) {
        ComposeStatusResult result = videoComposeService.queryComposeStatus(request);
        return success(result);
    }
}
