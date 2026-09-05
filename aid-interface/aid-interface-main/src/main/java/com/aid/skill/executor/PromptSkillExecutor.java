package com.aid.skill.executor;

import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.MediaTextStreamSink;
import com.aid.media.provider.TextReasoningOptionsResolver;
import com.aid.media.provider.StructuredOutputSupport;
import lombok.RequiredArgsConstructor;
import cn.hutool.crypto.SecureUtil;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 使用统一文本媒体任务执行Prompt型Skill。 */
@Component
@RequiredArgsConstructor
public class PromptSkillExecutor implements SkillExecutor {

    private static final String BIZ_TASK_TYPE = "SKILL_RUNTIME_STEP";
    private final IMediaGenerationService mediaGenerationService;

    @Override
    public String executorType() {
        return "PROMPT";
    }

    @Override
    public void execute(SkillExecutionContext context, SkillExecutionCallbacks callbacks) {
        AidRequestBuilder builder = new AidRequestBuilder(context);
        mediaGenerationService.generateTextStream(builder.build(), new MediaTextStreamSink() {
            @Override
            public void onTaskPrepared(long taskId) {
                callbacks.onTaskPrepared(taskId);
            }

            @Override
            public void onDetached(long taskId) {
                callbacks.onDetached(taskId);
            }

            @Override
            public void onDelta(String content) {
                callbacks.onDelta(content);
            }

            @Override
            public void onReasoningDelta(String content) {
                callbacks.onReasoningDelta(content);
            }

            @Override
            public void onDone(String fullText, String truncatedRawSnapshot) {
                callbacks.onDone(fullText);
            }

            @Override
            public void onFailed(String userMessage) {
                callbacks.onFailed(userMessage);
            }
        });
    }

    /** 只构造受控内部参数，调用方不能透传供应商原生options。 */
    private static class AidRequestBuilder {
        private final SkillExecutionContext context;

        AidRequestBuilder(SkillExecutionContext context) {
            this.context = context;
        }

        MediaTextGenerateRequest build() {
            boolean routing = "ROUTING".equals(context.getResponseMode());
            boolean reasoningEnabled = !routing
                    && Boolean.TRUE.equals(context.getRun().getEffectiveReasoningEnabled());
            Integer reasoningBudgetTokens = context.getRun().getReasoningBudgetTokens();
            MediaTextGenerateRequest request = new MediaTextGenerateRequest();
            request.setModelName(context.getSkill().getModelCode());
            request.setStream(true);
            request.setReasoningEnabled(reasoningEnabled);
            request.setReasoningLevel(reasoningEnabled
                    ? context.getRun().getEffectiveReasoningLevel() : null);
            request.setReasoningBudgetTokens(reasoningEnabled && reasoningBudgetTokens != null
                    && reasoningBudgetTokens > 0 ? reasoningBudgetTokens : null);
            request.setIncludeReasoning(reasoningEnabled
                    && Boolean.TRUE.equals(context.getRun().getShowReasoning()));
            request.setMessages(context.getMessages());
            request.setBizTaskType(context.getBizTaskType() == null ? BIZ_TASK_TYPE : context.getBizTaskType());
            request.setBizTaskId(context.getRun().getId());
            request.setBillingExempt(context.getCallIdentity() != null
                    && context.getCallIdentity().startsWith("step=format-repair,"));
            request.setUserId(context.getRun().getUserId());
            request.setProjectId(context.getProjectId());
            request.setEpisodeId(context.getEpisodeId());
            request.setCallId(context.getLogicalCallKey() != null ? context.getLogicalCallKey()
                    : SecureUtil.sha256("skill-runtime|" + context.getRun().getId() + "|main|0"));
            request.setCallIdentity(context.getCallIdentity());
            request.setTaskPromptDigest(request.getBizTaskType() + ":" + context.getRun().getId());
            Map<String, Object> options = new LinkedHashMap<>();
            options.put(StructuredOutputSupport.ENABLED_KEY, routing);
            if (context.getSkill().getMaxOutputTokens() != null) {
                options.put(TextReasoningOptionsResolver.MAX_OUTPUT_TOKENS_KEY,
                        routing ? Math.min(context.getSkill().getMaxOutputTokens(), 2048)
                                : context.getSkill().getMaxOutputTokens());
            }
            request.setOptions(options);
            return request;
        }
    }
}
