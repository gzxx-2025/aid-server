package com.aid.skill.service.impl;

import com.aid.skill.domain.AidSkill;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.service.ISkillCatalogService;
import com.aid.skill.service.SkillRuntimeCapabilities;
import com.aid.skill.service.SkillRuntimeEntrypointReadinessService;
import com.aid.skill.service.SkillModelService;
import com.aid.skill.vo.SkillCatalogVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Catalog backed only by active, versioned Runtime entrypoints. */
@Service
@RequiredArgsConstructor
public class SkillCatalogServiceImpl implements ISkillCatalogService {
    private static final String NORMAL = "0";

    private final AidSkillMapper skillMapper;
    private final SkillRuntimeEntrypointReadinessService readinessService;
    private final SkillModelService skillModelService;

    @Override
    public List<SkillCatalogVO.Item> listEntrypoints() {
        List<AidSkill> candidates = skillMapper.selectList(Wrappers.<AidSkill>lambdaQuery()
                        .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getName,
                                AidSkill::getDescription, AidSkill::getCapabilityDescription,
                                AidSkill::getIconUrl,
                                AidSkill::getOwnerType, AidSkill::getVisibility,
                                AidSkill::getInvocationScope, AidSkill::getCurrentVersionId,
                                AidSkill::getStatus, AidSkill::getDelFlag)
                        .eq(AidSkill::getOwnerType, SkillRuntimeCapabilities.OWNER_PLATFORM)
                        .eq(AidSkill::getVisibility, SkillRuntimeCapabilities.VISIBILITY_PUBLIC)
                        .eq(AidSkill::getInvocationScope, SkillRuntimeCapabilities.SCOPE_ENTRYPOINT)
                        .in(AidSkill::getSkillCode, SkillRuntimeCapabilities.CALLABLE_ENTRYPOINTS)
                        .isNotNull(AidSkill::getCurrentVersionId)
                        .eq(AidSkill::getStatus, NORMAL)
                        .eq(AidSkill::getDelFlag, NORMAL)
                        .orderByAsc(AidSkill::getId));
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, SkillRuntimeEntrypointReadinessService.ReadyEntrypoint> ready =
                readinessService.findReady(candidates);
        Map<Long, SkillModelService.CatalogProjection> modelProjections =
                skillModelService.catalogProjections(ready.entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().rootVersion(),
                        (left, right) -> left)));
        return candidates.stream().filter(skill -> ready.containsKey(skill.getId()))
                .map(skill -> toItem(skill, modelProjections.get(skill.getId()))).toList();
    }

    private SkillCatalogVO.Item toItem(AidSkill skill, SkillModelService.CatalogProjection models) {
        SkillRuntimeCapabilities.Descriptor descriptor =
                SkillRuntimeCapabilities.descriptor(skill.getSkillCode());
        SkillCatalogVO.Item item = new SkillCatalogVO.Item();
        item.setId(skill.getId());
        item.setSkillCode(skill.getSkillCode());
        item.setName(SkillRuntimeCapabilities.displayName(skill.getSkillCode(), skill.getName()));
        item.setDescription(skill.getDescription());
        item.setCapabilityDescription(skill.getCapabilityDescription());
        item.setIconUrl(skill.getIconUrl());
        item.setCapability(descriptor.capability());
        item.setOutputKind(descriptor.outputKind());
        if (models != null) {
            item.setDefaultModelCode(models.defaultModelCode());
            item.setModels(models.models());
        }
        return item;
    }
}
