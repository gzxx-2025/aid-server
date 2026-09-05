package com.aid.skill.service;

import com.aid.skill.dto.SkillAdminRequests;
import com.aid.skill.vo.SkillAdminVO;

import java.util.List;

/** Private administration for Skill identities and versioned Runtime audit. */
public interface ISkillAdminService {
    SkillAdminVO.PageResult<SkillAdminVO.SkillSummary> pageSkills(SkillAdminRequests.PageRequest request);
    void updateIdentity(SkillAdminRequests.IdentitySaveRequest request, Long operatorId, String operatorName);
    void updateStatus(SkillAdminRequests.StatusRequest request, Long operatorId, String operatorName);
    void deleteSkill(Long id, Long operatorId, String operatorName);
    void restoreSkill(Long id, Long operatorId, String operatorName);
    List<SkillAdminVO.TextModelOption> listTextModelOptions();
    SkillAdminVO.PageResult<SkillAdminVO.RunSummary> pageRuns(SkillAdminRequests.RunPageRequest request);
    SkillAdminVO.RunItem getRun(Long runId);
}
