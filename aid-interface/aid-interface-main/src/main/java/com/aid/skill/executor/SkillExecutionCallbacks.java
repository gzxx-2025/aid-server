package com.aid.skill.executor;

/** Skill实时执行回调。 */
public interface SkillExecutionCallbacks {
    void onTaskPrepared(long taskId);
    default void onDetached(long taskId) { }
    void onReasoningDelta(String content);
    void onDelta(String content);
    void onDone(String fullText);
    void onFailed(String message);
}
