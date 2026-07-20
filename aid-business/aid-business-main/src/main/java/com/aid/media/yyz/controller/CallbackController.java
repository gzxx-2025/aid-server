package com.aid.media.yyz.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.common.annotation.Anonymous;
import com.aid.common.core.domain.AjaxResult;
import com.aid.media.dto.CallbackRequest;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.service.TaskCompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.codec.Utf8;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;

/**
 * 上游回调接收端点：各供应商通过此入口通知任务完成。
 * 当前 DashScope 等供应商暂不支持原生回调，此端点预留，默认关闭；
 * 启用时必须配置回调令牌，伪造回调（篡改任务终态触发结算/退款、污染结果地址）在入口即被拒绝。
 */
@Slf4j
@RestController
@RequestMapping("/api/callback/media")
@RequiredArgsConstructor
public class CallbackController {

    private final AidMediaTaskMapper aidMediaTaskMapper;
    private final TaskCompletionService taskCompletionService;
    private final IAidAiModelService aidAiModelService;
    private final IAidAiProviderService aidAiProviderService;

    /** 通用回调开关：默认关闭（当前无供应商使用该预留端点，Vidu 走专用验签端点） */
    @Value("${media.callback.generic-enabled:false}")
    private boolean genericCallbackEnabled;

    /** 通用回调令牌：开启后必须配置，回调 URL 携带 ?token= 与之常量时间比对 */
    @Value("${media.callback.generic-token:}")
    private String genericCallbackToken;

    /**
     * 接收供应商回调通知。
     * URL 格式：POST /api/callback/media/{providerCode}?token=xxx
     * 显式 @Anonymous 不依赖 SecurityConfig 的 permitAll 白名单，防止配置变更后回调接口返 401。
     *
     * @param providerCode 供应商编码（如 dashscope、volcengine）
     * @param token        回调令牌（与配置 media.callback.generic-token 比对）
     * @param request      回调请求体（通用结构）
     */
    @Anonymous
    @PostMapping("/{providerCode}")
    public AjaxResult onCallback(@PathVariable String providerCode,
                                 @RequestParam(required = false) String token,
                                 @RequestBody CallbackRequest request) {
        log.info("收到回调通知, providerCode={}, providerTaskId={}, status={}",
            providerCode, request.getProviderTaskId(), request.getStatus());

        // 来源校验：端点默认关闭；开启后令牌缺配或不匹配一律拒绝（fail-close，防伪造终态）
        if (!genericCallbackEnabled) {
            log.warn("通用媒体回调端点未启用，已拒绝, providerCode={}", providerCode);
            return AjaxResult.success();
        }
        if (StrUtil.isBlank(genericCallbackToken) || !constantTimeEquals(genericCallbackToken, token)) {
            log.warn("通用媒体回调令牌校验失败, providerCode={}, providerTaskId={}",
                providerCode, request.getProviderTaskId());
            return AjaxResult.success();
        }

        if (StrUtil.isBlank(request.getProviderTaskId())) {
            log.warn("回调缺少 providerTaskId, providerCode={}", providerCode);
            return AjaxResult.success();
        }

        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AidMediaTask::getProviderTaskId, request.getProviderTaskId());
        // 仅查找非终态任务。
        wrapper.in(AidMediaTask::getStatus,
            MediaTaskStatus.WAIT_CALLBACK.name(),
            MediaTaskStatus.WAIT_POLL.name(),
            MediaTaskStatus.PROCESSING.name());
        wrapper.last("LIMIT 1");

        AidMediaTask task = aidMediaTaskMapper.selectOne(wrapper);
        if (task == null) {
            log.info("回调未找到待处理任务, providerTaskId={}", request.getProviderTaskId());
            return AjaxResult.success();
        }

        // 校验路径 providerCode 与任务实际归属供应商一致，反查失败（null）同样拒绝，避免校验形同虚设。
        String actualProviderCode = resolveProviderCodeByModelName(task.getModelName());
        if (!StrUtil.equals(providerCode, actualProviderCode)) {
            log.warn("回调供应商校验拒绝, pathProviderCode={}, actualProviderCode={}, taskId={}, providerTaskId={}",
                providerCode, actualProviderCode, task.getId(), request.getProviderTaskId());
            return AjaxResult.success();
        }

        ProviderTaskResult taskResult = ProviderTaskResult.builder()
            .status(request.getStatus())
            .resultUrl(request.getResultUrl())
            .errorMessage(request.getErrorMessage())
            .rawResponse(request.getRawPayload())
            .build();

        taskCompletionService.completeTask(task.getId(), taskResult);

        return AjaxResult.success();
    }

    /**
     * 常量时间字符串比对：避免逐字符短路比较被计时侧信道猜测令牌。
     *
     * @param expected 配置的令牌
     * @param actual   回调携带的令牌
     * @return true=一致
     */
    private boolean constantTimeEquals(String expected, String actual) {
        if (StrUtil.isBlank(actual)) {
            return false;
        }
        return MessageDigest.isEqual(Utf8.encode(expected), Utf8.encode(actual));
    }

    /**
     * 通过任务的 modelName 反查所属供应商编码。
     */
    private String resolveProviderCodeByModelName(String modelName) {
        if (StrUtil.isBlank(modelName)) {
            return null;
        }
        try {
            // 从 modelName 找到模型配置，再找其所属供应商。
            AidAiModel model = aidAiModelService.getOne(
                new LambdaQueryWrapper<AidAiModel>()
                    .eq(AidAiModel::getModelCode, modelName)
                    .last("LIMIT 1"),
                false
            );
            if (model == null || model.getProviderId() == null) {
                return null;
            }
            AidAiProvider provider = aidAiProviderService.getById(model.getProviderId());
            return provider != null ? provider.getProviderCode() : null;
        } catch (Exception ex) {
            log.warn("resolveProviderCodeByModelName 异常, modelName={}", modelName, ex);
            return null;
        }
    }
}
