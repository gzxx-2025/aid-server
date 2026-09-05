package com.aid.skill.service;

import com.aid.skill.dto.SkillInvocationRequests;
import com.aid.skill.vo.SkillInvocationVO;

import java.util.List;

/** 所有 Web/Open API/CLI 适配器共用的唯一 Skill 调用服务。 */
public interface ISkillInvocationService {
    SkillInvocationVO invoke(SkillInvocationRequests.InvokeRequest request, Long userId,
                             String operator, String invokeSource);

    SkillInvocationVO respond(SkillInvocationRequests.RespondRequest request, Long userId, String operator);

    SkillInvocationVO getRun(Long runId, Long userId);

    SkillInvocationVO.HistoryPage listHistory(SkillInvocationRequests.HistoryRequest request, Long userId);

    List<SkillInvocationVO.EventView> listEvents(SkillInvocationRequests.EventPageRequest request, Long userId);

    void cancel(Long runId, Long userId, String operator);

    void reconcileMediaTask(Long mediaTaskId);

    void reconcileStaleRuns();
}
