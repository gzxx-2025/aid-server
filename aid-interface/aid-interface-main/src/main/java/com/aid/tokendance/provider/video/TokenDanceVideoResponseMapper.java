package com.aid.tokendance.provider.video;

import cn.hutool.core.util.StrUtil;
import com.aid.common.error.TaskErrorResult;
import com.aid.media.provider.ProviderErrorSanitizer;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.tokendance.provider.common.TokenDanceHttpResponse;
import com.aid.tokendance.provider.common.TokenDanceResponseMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** TokenDance 各视频协议的提交、查询和 usage 归一化。 */
final class TokenDanceVideoResponseMapper
{
    enum Shape { SEEDANCE, KLING, DASHSCOPE, MINIMAX }

    private TokenDanceVideoResponseMapper()
    {
    }

    static ProviderSubmitResult submit(TokenDanceHttpResponse response, Shape shape)
    {
        String audit = TokenDanceResponseMapper.auditBody(response);
        if (response == null || !response.isSuccessful())
        {
            return ProviderSubmitResult.builder().rawResponse(audit).build();
        }
        JsonNode root = TokenDanceResponseMapper.readTree(response.bodyUtf8());
        String taskId = switch (shape)
        {
            case SEEDANCE -> text(root, "id", "task_id", "data.id");
            case KLING -> text(root, "data.id", "id", "task_id");
            case DASHSCOPE -> text(root, "output.task_id", "task_id");
            case MINIMAX -> text(root, "task_id", "task.id", "id");
        };
        return ProviderSubmitResult.builder().providerTaskId(taskId).rawResponse(audit).build();
    }

    static ProviderTaskResult query(TokenDanceHttpResponse response, Shape shape)
    {
        String audit = TokenDanceResponseMapper.auditBody(response);
        if (response == null || !response.isSuccessful())
        {
            String message = TokenDanceResponseMapper.errorMessage(response, "上游查询暂不可用");
            return ProviderTaskResult.builder()
                    .status("PROCESSING")
                    .errorMessage(message)
                    .rawErrorMessage(message)
                    .rawResponse(audit)
                    .taskError(TokenDanceResponseMapper.recoveryError(response))
                    .querySuccessful(Boolean.FALSE)
                    .terminalConfirmed(Boolean.FALSE)
                    .build();
        }
        JsonNode root = TokenDanceResponseMapper.readTree(response.bodyUtf8());
        String providerStatus = switch (shape)
        {
            case SEEDANCE, KLING -> text(root, "status", "data.status");
            case DASHSCOPE -> text(root, "output.task_status", "task_status");
            case MINIMAX -> text(root, "task.status", "status");
        };
        String status = normalizeStatus(shape, providerStatus);
        if (status == null)
        {
            return ProviderTaskResult.builder()
                    .status("PROCESSING")
                    .errorMessage("上游状态待确认")
                    .rawResponse(audit)
                    .providerStatus(providerStatus)
                    .querySuccessful(Boolean.FALSE)
                    .terminalConfirmed(Boolean.FALSE)
                    .build();
        }
        if ("PROCESSING".equals(status))
        {
            return ProviderTaskResult.builder()
                    .status(status)
                    .rawResponse(audit)
                    .providerStatus(providerStatus)
                    .querySuccessful(Boolean.TRUE)
                    .terminalConfirmed(Boolean.FALSE)
                    .build();
        }
        if ("FAILED".equals(status))
        {
            String rawError = text(root, "message", "error.message", "output.message",
                    "task.error.message", "task.message");
            TaskErrorResult recovery = TokenDanceResponseMapper.recoveryError(response);
            return ProviderTaskResult.builder()
                    .status(status)
                    .errorMessage(recovery == null ? "上游任务执行失败" : recovery.getUserMessage())
                    .rawErrorMessage(ProviderErrorSanitizer.safeMessage(rawError, "上游任务执行失败"))
                    .rawResponse(audit)
                    .taskError(recovery)
                    .providerStatus(providerStatus)
                    .querySuccessful(Boolean.TRUE)
                    .terminalConfirmed(Boolean.TRUE)
                    .build();
        }
        Result result = result(root, shape);
        if (StrUtil.isBlank(result.url))
        {
            return ProviderTaskResult.builder()
                    .status("PROCESSING")
                    .errorMessage("上游产物尚未就绪")
                    .rawResponse(audit)
                    .providerStatus(providerStatus)
                    .querySuccessful(Boolean.FALSE)
                    .terminalConfirmed(Boolean.FALSE)
                    .build();
        }
        return ProviderTaskResult.builder()
                .status("SUCCEEDED")
                .resultUrl(result.url)
                .videoDurationSeconds(result.outputSeconds)
                .inputVideoSeconds(result.inputSeconds)
                .inputImageCount(result.inputImageCount)
                .completionTokens(result.completionTokens)
                .totalTokens(result.totalTokens)
                .rawResponse(audit)
                .providerStatus(providerStatus)
                .querySuccessful(Boolean.TRUE)
                .terminalConfirmed(Boolean.TRUE)
                .build();
    }

    private static Result result(JsonNode root, Shape shape)
    {
        String url = switch (shape)
        {
            case SEEDANCE -> text(root, "content.video_url", "video_url", "output.video_url");
            case KLING -> klingVideoUrl(root);
            case DASHSCOPE -> text(root, "output.video_url", "video_url");
            case MINIMAX -> text(root, "task.content.url", "content.url", "video_url");
        };
        Integer outputSeconds = switch (shape)
        {
            case KLING -> klingVideoDuration(root);
            case SEEDANCE -> TokenDanceResponseMapper.firstPositive(root,
                    "content.duration", "usage.output_video_duration", "usage.video_duration");
            case DASHSCOPE -> TokenDanceResponseMapper.firstPositive(root,
                    "usage.output_video_duration", "output.duration", "usage.duration");
            case MINIMAX -> TokenDanceResponseMapper.firstPositive(root,
                    "task.content.duration", "usage.output_video_duration", "usage.video_duration");
        };
        Integer inputSeconds = TokenDanceResponseMapper.firstPositive(root,
                "usage.input_video_duration", "usage.input_video_seconds");
        Integer inputImages = TokenDanceResponseMapper.firstNonNegative(root.path("usage"),
                "input_image_count", "input_images");
        Integer completion = TokenDanceResponseMapper.firstNonNegative(root.path("usage"),
                "completion_tokens", "output_tokens");
        Integer total = TokenDanceResponseMapper.firstNonNegative(root.path("usage"), "total_tokens");
        return new Result(safeHttpUrl(url), outputSeconds, inputSeconds, inputImages, completion, total);
    }

    private static String safeHttpUrl(String value)
    {
        if (StrUtil.isBlank(value))
        {
            return null;
        }
        try
        {
            URI uri = URI.create(value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || StrUtil.isBlank(uri.getHost()) || uri.getUserInfo() != null)
            {
                return null;
            }
            return uri.toString();
        }
        catch (IllegalArgumentException exception)
        {
            return null;
        }
    }

    private static String klingVideoUrl(JsonNode root)
    {
        if (root == null)
        {
            return null;
        }
        for (JsonNode data : root.path("data"))
        {
            for (JsonNode output : data.path("outputs"))
            {
                if ("video".equalsIgnoreCase(output.path("type").asText()))
                {
                    return StrUtil.trimToNull(output.path("url").asText(null));
                }
            }
        }
        return null;
    }

    private static Integer klingVideoDuration(JsonNode root)
    {
        if (root == null)
        {
            return null;
        }
        for (JsonNode data : root.path("data"))
        {
            for (JsonNode output : data.path("outputs"))
            {
                if ("video".equalsIgnoreCase(output.path("type").asText()))
                {
                    return output.path("duration").isNumber()
                            ? (int) Math.ceil(output.path("duration").doubleValue()) : null;
                }
            }
        }
        return null;
    }

    private static String normalizeStatus(Shape shape, String raw)
    {
        if (StrUtil.isBlank(raw))
        {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        Set<String> processing = Set.of("queued", "queueing", "submitted", "pending", "running", "processing");
        Set<String> succeeded = Set.of("succeeded", "success", "completed");
        Set<String> failed = Set.of("failed", "failure", "cancelled", "canceled", "expired");
        if (processing.contains(value)) return "PROCESSING";
        if (succeeded.contains(value)) return "SUCCEEDED";
        if (failed.contains(value)) return "FAILED";
        return null;
    }

    private static String text(JsonNode root, String... paths)
    {
        if (root == null)
        {
            return null;
        }
        for (String path : paths)
        {
            JsonNode current = root;
            for (String part : path.split("\\."))
            {
                current = current == null ? null : current.path(part);
            }
            if (current != null && current.isValueNode() && StrUtil.isNotBlank(current.asText()))
            {
                return current.asText().trim();
            }
        }
        return null;
    }

    private record Result(String url, Integer outputSeconds, Integer inputSeconds,
            Integer inputImageCount, Integer completionTokens, Integer totalTokens)
    {
    }
}
