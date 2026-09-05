package com.aid.skill.executor;

import com.aid.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Skill执行器注册表。 */
@Component
public class SkillExecutorRegistry {
    private final Map<String, SkillExecutor> executors;

    public SkillExecutorRegistry(List<SkillExecutor> values) {
        this.executors = values.stream().collect(Collectors.toUnmodifiableMap(
                value -> value.executorType().toUpperCase(Locale.ROOT), Function.identity()));
    }

    public SkillExecutor getRequired(String type) {
        SkillExecutor executor = type == null ? null : executors.get(type.toUpperCase(Locale.ROOT));
        if (executor == null) {
            throw new ServiceException("执行器不可用");
        }
        return executor;
    }
}
