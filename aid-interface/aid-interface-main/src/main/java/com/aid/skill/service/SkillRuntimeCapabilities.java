package com.aid.skill.service;

import java.util.Map;
import java.util.Set;

/** Entrypoints implemented by the current Runtime orchestrator. */
public final class SkillRuntimeCapabilities {
    public static final String SCREENPLAY = "screenplay";
    public static final String SCREENPLAY_WRITE = "screenplay-write";
    public static final String SCREENPLAY_REVIEW = "screenplay-review";
    public static final String RETIRED_SCREENPLAY_CHAT = "screenplay_writer";
    public static final String OWNER_PLATFORM = "PLATFORM";
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String SCOPE_ENTRYPOINT = "ENTRYPOINT";
    public static final String CAPABILITY_SCRIPT_WRITING = "SCRIPT_WRITING";
    public static final String OUTPUT_SCREENPLAY = "SCREENPLAY";
    private static final Map<String, Descriptor> DESCRIPTORS = Map.of(
            SCREENPLAY, new Descriptor(CAPABILITY_SCRIPT_WRITING, OUTPUT_SCREENPLAY));
    public static final Set<String> CALLABLE_ENTRYPOINTS = DESCRIPTORS.keySet();

    private SkillRuntimeCapabilities() { }

    public static boolean supports(String skillCode) {
        return DESCRIPTORS.containsKey(skillCode);
    }

    public static Descriptor descriptor(String skillCode) {
        return DESCRIPTORS.get(skillCode);
    }

    /** Stable labels distinguish the callable root from its non-callable children. */
    public static String displayName(String skillCode, String fallback) {
        if (SCREENPLAY.equals(skillCode)
                && isSeedValue(fallback, "剧本创作", "专业剧本创作")) {
            return "剧本创作入口";
        }
        if (SCREENPLAY_WRITE.equals(skillCode) && isSeedValue(fallback, "剧本写作")) {
            return "剧本写作子 Skill";
        }
        if (SCREENPLAY_REVIEW.equals(skillCode) && isSeedValue(fallback, "剧本审核")) {
            return "剧本审核子 Skill";
        }
        return fallback;
    }

    public static String displayDescription(String skillCode, String fallback) {
        if (SCREENPLAY.equals(skillCode) && isSeedValue(fallback,
                "电影与剧集剧本创作、审核及规范化入口")) {
            return "ENTRYPOINT 根 Skill：负责剧本创作校验、动态澄清和固定版本写作/审核编排。";
        }
        if (SCREENPLAY_WRITE.equals(skillCode) && isSeedValue(fallback,
                "INTERNAL：CREATE/REWRITE/CONTINUE/NORMALIZE/REPAIR")) {
            return "INTERNAL 写作子 Skill：仅由入口编排，执行创建、改写、续写、规范化和修复。";
        }
        if (SCREENPLAY_REVIEW.equals(skillCode) && isSeedValue(fallback,
                "INTERNAL：独立只读审核，不直接改稿")) {
            return "INTERNAL 审核子 Skill：仅由入口编排，独立只读审核，不直接改稿。";
        }
        return fallback;
    }

    private static boolean isSeedValue(String value, String... candidates) {
        if (value == null || value.isBlank()) {
            return true;
        }
        for (String candidate : candidates) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public record Descriptor(String capability, String outputKind) { }
}
