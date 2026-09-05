package com.aid.skill.task;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporary scheduler-name alias used only while the Runtime-only migration is being applied. */
@Configuration
public class SkillRuntimeTaskAliasConfiguration {
    private static final String RUNTIME_TASK = "skillRuntimeTask";
    private static final String PREVIOUS_TASK_NAME = "skillChatTask";

    @Bean
    public static BeanFactoryPostProcessor skillRuntimeTaskAliasRegistrar() {
        return beanFactory -> {
            if (beanFactory instanceof BeanDefinitionRegistry registry
                    && registry.containsBeanDefinition(RUNTIME_TASK)
                    && !registry.isAlias(PREVIOUS_TASK_NAME)
                    && !registry.containsBeanDefinition(PREVIOUS_TASK_NAME)) {
                registry.registerAlias(RUNTIME_TASK, PREVIOUS_TASK_NAME);
            }
        };
    }
}
