package com.aid.skill.executor;

/** Skill执行器扩展点。 */
public interface SkillExecutor {
    String executorType();
    void execute(SkillExecutionContext context, SkillExecutionCallbacks callbacks);
}
