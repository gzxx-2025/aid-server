package com.aid.skill.executor;

import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillRun;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Skill单次执行上下文；配置读取自当前 Skill。 */
@Data
@Builder
public class SkillExecutionContext {
    private AidSkillRun run;
    private AidSkill skill;
    private List<MediaTextGenerateRequest.TextMessageItem> messages;
    private List<SkillInputPart> inputParts;
    private String responseMode;
    private Long projectId;
    private Long episodeId;
    private String logicalCallKey;
    private String callIdentity;
    private String bizTaskType;
}
