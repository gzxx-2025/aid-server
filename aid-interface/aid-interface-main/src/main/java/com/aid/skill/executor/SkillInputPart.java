package com.aid.skill.executor;

import lombok.Builder;
import lombok.Value;

/** 经过会话层校验和规范化的通用 Skill 输入部件。 */
@Value
@Builder
public class SkillInputPart {
    String type;
    String slot;
    String text;
    String resourceUrl;
    String thumbnailUrl;
    String mimeType;
    String metadataJson;
    String contextScope;
}
