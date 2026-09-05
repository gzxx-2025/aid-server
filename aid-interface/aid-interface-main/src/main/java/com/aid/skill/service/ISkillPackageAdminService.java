package com.aid.skill.service;

import com.aid.skill.dto.SkillPackageAdminRequests;
import com.aid.skill.vo.SkillAdminVO;
import com.aid.skill.vo.SkillPackageAdminVO;

import java.util.List;

/** Skill 草稿、校验和不可变版本发布管理。 */
public interface ISkillPackageAdminService {
    SkillPackageAdminVO.VersionPageResult listVersions(
            SkillPackageAdminRequests.VersionPageRequest request);
    SkillPackageAdminVO.VersionDetail getVersion(Long versionId);
    SkillAdminVO.PageResult<SkillPackageAdminVO.DependencySkillOption> listDependencyOptions(
            SkillPackageAdminRequests.DependencySkillPageRequest request);
    SkillAdminVO.PageResult<SkillPackageAdminVO.DependencyVersionOption> listDependencyVersionOptions(
            SkillPackageAdminRequests.DependencyVersionPageRequest request);
    List<SkillPackageAdminVO.DependencyLabel> listDependencyLabels(
            SkillPackageAdminRequests.DependencyLabelRequest request);
    SkillPackageAdminVO.DraftDetail getDraft(Long skillId, Long baseVersionId, Long operatorId);
    SkillPackageAdminVO.DraftDetail saveDraft(SkillPackageAdminRequests.DraftSaveRequest request,
                                              Long operatorId, String operatorName);
    SkillPackageAdminVO.ValidationResult validateDraft(SkillPackageAdminRequests.PackagePayload request);
    void discard(SkillPackageAdminRequests.DraftDiscardRequest request,
                 Long operatorId, String operatorName);
    SkillPackageAdminVO.VersionDetail publish(SkillPackageAdminRequests.DraftPublishRequest request,
                                              Long operatorId, String operatorName);
    void activate(SkillPackageAdminRequests.VersionActivateRequest request,
                  Long operatorId, String operatorName);
}
