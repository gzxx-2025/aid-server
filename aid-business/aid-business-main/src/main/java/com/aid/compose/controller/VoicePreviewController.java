package com.aid.compose.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.compose.dto.VoicePreviewRequest;
import com.aid.compose.dto.VoicePreviewResult;
import com.aid.compose.service.VoicePreviewService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 接口3：试听（C 端，同步、免费、不落库）。
 * 文字转语音同步试听：minimax 同步直返、豆包短轮询取结果；
 * 文本最大字数由 aid_config（voice / voice_preview_max_seconds）试听秒数折算（×4.5 字/秒），缺省 15 字；
 * 不落库、不进 aid_media_task、不产生任何余额流水/消费记录。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/voice/preview")
public class VoicePreviewController extends BaseController {

    /** 试听服务 */
    @Resource
    private VoicePreviewService voicePreviewService;

    /**
     * 同步试听。
     * URL：POST /api/user/voice/preview
     *
     * @param request 试听入参（text 字数上限随试听秒数配置折算 + 音色模型ID + 音色编码）
     * @return 试听音频结果（临时 URL 或 base64，含可选时长）
     */
    @PostMapping
    public AjaxResult preview(@RequestBody VoicePreviewRequest request) {
        VoicePreviewResult result = voicePreviewService.preview(request);
        return success(result);
    }
}
