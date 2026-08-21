package com.aid.media.provider;

import com.aid.media.constants.KlingConstants;

import java.util.Locale;
import java.util.Set;

/** 可灵任务状态严格映射；文档外状态不推断终态。 */
public final class KlingStatusMapper {

    private static final Set<String> KNOWN = Set.of("submitted", "processing", "succeeded", "succeed", "failed");

    private KlingStatusMapper() {
    }

    public static boolean isKnown(String raw) {
        return raw != null && KNOWN.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isTerminal(String raw) {
        if (raw == null) {
            return false;
        }
        String state = raw.trim().toLowerCase(Locale.ROOT);
        return "succeeded".equals(state) || "succeed".equals(state) || "failed".equals(state);
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return KlingConstants.TASK_STATUS_PROCESSING;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "succeeded", "succeed" -> KlingConstants.TASK_STATUS_SUCCEEDED;
            case "failed" -> KlingConstants.TASK_STATUS_FAILED;
            default -> KlingConstants.TASK_STATUS_PROCESSING;
        };
    }
}
