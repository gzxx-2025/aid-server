package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.media.constants.MinimaxH3Constants;

import java.util.Locale;
import java.util.Set;

/** MiniMax H3 官方状态到统一媒体任务状态的映射。 */
public final class MinimaxH3StatusMapper {

    private static final Set<String> KNOWN = Set.of(
        MinimaxH3Constants.STATUS_QUEUED, MinimaxH3Constants.STATUS_RUNNING,
        MinimaxH3Constants.STATUS_SUCCEEDED, MinimaxH3Constants.STATUS_FAILED,
        MinimaxH3Constants.STATUS_CANCELLED);

    private MinimaxH3StatusMapper() {
    }

    public static boolean isKnown(String status) {
        return KNOWN.contains(normalizeRaw(status));
    }

    public static boolean isTerminal(String status) {
        String value = normalizeRaw(status);
        return MinimaxH3Constants.STATUS_SUCCEEDED.equals(value)
            || MinimaxH3Constants.STATUS_FAILED.equals(value)
            || MinimaxH3Constants.STATUS_CANCELLED.equals(value);
    }

    public static String normalize(String status) {
        return switch (normalizeRaw(status)) {
            case MinimaxH3Constants.STATUS_SUCCEEDED -> MinimaxH3Constants.STATUS_SUCCESS;
            case MinimaxH3Constants.STATUS_FAILED, MinimaxH3Constants.STATUS_CANCELLED -> MinimaxH3Constants.STATUS_FAILURE;
            default -> MinimaxH3Constants.STATUS_PROCESSING;
        };
    }

    private static String normalizeRaw(String status) {
        return StrUtil.trimToEmpty(status).toLowerCase(Locale.ROOT);
    }
}
