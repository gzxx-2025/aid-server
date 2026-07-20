package com.aid.rps.voice.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.common.utils.DateUtils;
import com.aid.rps.voice.service.IRoleVoiceAutoBindService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 角色音色「自动匹配绑定」Service 实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class RoleVoiceAutoBindServiceImpl implements IRoleVoiceAutoBindService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String STATUS_NORMAL = "0";
    private static final String ASSET_TYPE_CHARACTER = "character";
    private static final String CREATE_SOURCE_AUTO = "auto";

    /** 角色性别 → 音色性别映射目标值 */
    private static final String VOICE_GENDER_MALE = "male";
    private static final String VOICE_GENDER_FEMALE = "female";
    private static final String VOICE_GENDER_NEUTRAL = "neutral";

    /** 音色年龄档枚举顺序（用于相邻档判定） */
    private static final List<String> AGE_BUCKET_ORDER =
            List.of("child", "teen", "young", "adult", "middle", "elderly");

    /**
     * 中文人设关键词（同时在「角色名/介绍」与音色 character_types 命中则加分）。
     * 用于让匹配更贴角色气质，而不仅按年龄性别。
     */
    private static final List<String> PERSONA_KEYWORDS = List.of(
            "少女", "少年", "青年", "御姐", "大叔", "萝莉", "正太", "大妈",
            "老人", "老者", "少妇", "学生", "萌娃", "熟女", "大爷", "奶奶", "爷爷");
    @Resource
    private IAidRolePropSceneService rpsService;

    @Resource
    private IAidAiVoiceLibraryService voiceLibraryService;

    @Resource
    private IAidRoleVoiceBindingService bindingService;

    @Resource
    private IAidAiModelService modelService;

    @Resource
    private IAidAiProviderService providerService;
    @Override
    public void autoBindForEpisode(Long projectId, Long episodeId, Long userId)
    {
        if (Objects.isNull(projectId) || Objects.isNull(episodeId) || Objects.isNull(userId))
        {
            return;
        }
        // 项目级角色目录：剧集角色主资产项目内唯一（episodeId=0），按项目查询同时覆盖全局角色与历史按集角色
        List<AidRolePropScene> characters = rpsService.list(Wrappers.<AidRolePropScene>lambdaQuery()
                .eq(AidRolePropScene::getProjectId, projectId)
                .eq(AidRolePropScene::getUserId, userId)
                .eq(AidRolePropScene::getAssetType, ASSET_TYPE_CHARACTER)
                .eq(AidRolePropScene::getCreateSource, CREATE_SOURCE_AUTO)
                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                .orderByAsc(AidRolePropScene::getId));
        if (CollectionUtil.isEmpty(characters))
        {
            log.info("角色音色自动绑定：无自动提取角色, projectId={}, episodeId={}", projectId, episodeId);
            return;
        }

        List<AidAiVoiceLibrary> pool = loadUsableVoicePool();
        if (CollectionUtil.isEmpty(pool))
        {
            log.warn("角色音色自动绑定：无可用音色, projectId={}, episodeId={}", projectId, episodeId);
            return;
        }

        Set<Long> usedVoiceIds = new HashSet<>();
        int bound = 0;
        for (AidRolePropScene role : characters)
        {
            try
            {
                AidAiVoiceLibrary picked = pickBestVoice(role, pool, usedVoiceIds);
                if (Objects.isNull(picked))
                {
                    continue;
                }
                upsertBinding(role, picked, userId);
                usedVoiceIds.add(picked.getId());
                bound++;
            }
            catch (Exception e)
            {
                log.error("角色音色自动绑定单个失败（跳过）: assetId={}, err={}", role.getId(), e.getMessage(), e);
            }
        }
        log.info("角色音色自动绑定完成: projectId={}, episodeId={}, 角色数={}, 成功绑定={}",
                projectId, episodeId, characters.size(), bound);
    }

    @Override
    public boolean rematchForCharacter(Long assetId, Long userId)
    {
        if (Objects.isNull(assetId) || Objects.isNull(userId))
        {
            return false;
        }
        AidRolePropScene role = rpsService.getById(assetId);
        if (Objects.isNull(role) || !Objects.equals(DEL_FLAG_NORMAL, role.getDelFlag())
                || !Objects.equals(userId, role.getUserId())
                || !Objects.equals(ASSET_TYPE_CHARACTER, role.getAssetType()))
        {
            return false;
        }
        // 仅自动提取角色参与即时重绑；手动角色由用户自行绑定
        if (!Objects.equals(CREATE_SOURCE_AUTO, role.getCreateSource()))
        {
            return false;
        }
        List<AidAiVoiceLibrary> pool = loadUsableVoicePool();
        if (CollectionUtil.isEmpty(pool))
        {
            return false;
        }
        // 同剧集已占用的音色尽量避开；当前角色自己的旧绑定保留在占用集合中，
        // 让"重新匹配"倾向换一个新音色（仅当无其它候选时才会命中原音色）
        Set<Long> usedVoiceIds = loadEpisodeUsedVoiceIds(role, userId);
        AidRoleVoiceBinding current = findActiveBinding(assetId);
        AidAiVoiceLibrary picked = pickBestVoice(role, pool, usedVoiceIds);
        if (Objects.isNull(picked))
        {
            return false;
        }
        // 与当前绑定相同则不动
        if (Objects.nonNull(current) && Objects.equals(picked.getId(), current.getVoiceLibraryId()))
        {
            return false;
        }
        upsertBinding(role, picked, userId);
        return true;
    }
    /**
     * 加载可用音色池：{@code del_flag=0 + status=启用 + offline_time>NOW()}，
     * 且所属模型 + 所属供应商均为启用状态（供应商/模型停用一律剔除）。
     */
    private List<AidAiVoiceLibrary> loadUsableVoicePool()
    {
        List<AidAiVoiceLibrary> voices = voiceLibraryService.list(Wrappers.<AidAiVoiceLibrary>lambdaQuery()
                .eq(AidAiVoiceLibrary::getDelFlag, DEL_FLAG_NORMAL)
                .eq(AidAiVoiceLibrary::getStatus, STATUS_NORMAL)
                .gt(AidAiVoiceLibrary::getOfflineTime, new Date()));
        if (CollectionUtil.isEmpty(voices))
        {
            return new ArrayList<>();
        }
        // 过滤：所属模型启用
        Set<Long> modelIds = voices.stream().map(AidAiVoiceLibrary::getModelId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> enabledModelIds = new HashSet<>();
        if (CollectionUtil.isNotEmpty(modelIds))
        {
            List<AidAiModel> models = modelService.list(Wrappers.<AidAiModel>lambdaQuery()
                    .select(AidAiModel::getId, AidAiModel::getStatus)
                    .in(AidAiModel::getId, modelIds));
            for (AidAiModel m : models)
            {
                if (Objects.equals(STATUS_NORMAL, m.getStatus()))
                {
                    enabledModelIds.add(m.getId());
                }
            }
        }
        // 过滤：所属供应商启用
        Set<Long> providerIds = voices.stream().map(AidAiVoiceLibrary::getProviderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> enabledProviderIds = new HashSet<>();
        if (CollectionUtil.isNotEmpty(providerIds))
        {
            List<AidAiProvider> providers = providerService.list(Wrappers.<AidAiProvider>lambdaQuery()
                    .select(AidAiProvider::getId, AidAiProvider::getStatus)
                    .in(AidAiProvider::getId, providerIds));
            for (AidAiProvider p : providers)
            {
                if (Objects.equals(STATUS_NORMAL, p.getStatus()))
                {
                    enabledProviderIds.add(p.getId());
                }
            }
        }
        return voices.stream()
                .filter(v -> Objects.nonNull(v.getModelId()) && enabledModelIds.contains(v.getModelId()))
                .filter(v -> Objects.nonNull(v.getProviderId()) && enabledProviderIds.contains(v.getProviderId()))
                .collect(Collectors.toList());
    }

    /** 加载同角色所在剧集内、其它角色已占用的音色ID集合（用于去重避让）。 */
    private Set<Long> loadEpisodeUsedVoiceIds(AidRolePropScene role, Long userId)
    {
        List<AidRoleVoiceBinding> bindings = bindingService.list(Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                .select(AidRoleVoiceBinding::getAssetId, AidRoleVoiceBinding::getVoiceLibraryId)
                .eq(AidRoleVoiceBinding::getProjectId, role.getProjectId())
                .eq(AidRoleVoiceBinding::getEpisodeId, role.getEpisodeId())
                .eq(AidRoleVoiceBinding::getUserId, userId)
                .eq(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_NORMAL)
                .eq(AidRoleVoiceBinding::getStatus, STATUS_NORMAL));
        return bindings.stream().map(AidRoleVoiceBinding::getVoiceLibraryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
    }
    /**
     * 为角色挑选最佳音色：gender 硬过滤 → 年龄档 + 人设标签 + 用途打分 → 去重避让 → 最高分集合内均匀随机。
     * 同分随机（而非 sort_order/id 确定性兜底）是刻意设计：打分维度较粗，同一性别年龄档下常有大量音色同分，
     * 确定性排序会让所有用户永远命中同一批头部音色；在最高分候选集合内均匀随机可公平轮换，
     * 且不破坏"性别/年龄/人设匹配优先"的语义。
     */
    private AidAiVoiceLibrary pickBestVoice(AidRolePropScene role, List<AidAiVoiceLibrary> pool, Set<Long> usedVoiceIds)
    {
        String targetGender = mapGender(role.getGender());
        // gender 硬过滤候选池
        List<AidAiVoiceLibrary> genderPool = pool.stream()
                .filter(v -> Objects.equals(targetGender, normalizeVoiceGender(v.getGender())))
                .collect(Collectors.toList());
        // 无同性别候选时兜底放开到全池（保证仍能绑一个）
        List<AidAiVoiceLibrary> candidates = CollectionUtil.isNotEmpty(genderPool) ? genderPool : pool;

        Integer targetBucketIdx = ageBucketIndex(parseAgeNumber(role.getAgeRange()));
        String roleText = StrUtil.nullToEmpty(role.getName()) + " " + StrUtil.nullToEmpty(role.getIntroduction());

        // 第一遍：打分并记录最高分
        int bestScore = Integer.MIN_VALUE;
        List<AidAiVoiceLibrary> bestCandidates = new ArrayList<>();
        for (AidAiVoiceLibrary v : candidates)
        {
            int score = scoreVoice(v, targetBucketIdx, roleText, usedVoiceIds);
            if (score > bestScore)
            {
                bestScore = score;
                bestCandidates.clear();
                bestCandidates.add(v);
            }
            else if (score == bestScore)
            {
                bestCandidates.add(v);
            }
        }
        if (CollectionUtil.isEmpty(bestCandidates))
        {
            return null;
        }
        // 最高分集合内均匀随机，保证同分音色被公平轮换
        return bestCandidates.get(ThreadLocalRandom.current().nextInt(bestCandidates.size()));
    }

    private int scoreVoice(AidAiVoiceLibrary v, Integer targetBucketIdx, String roleText, Set<Long> usedVoiceIds)
    {
        int score = 0;
        // 年龄档：命中 +3，相邻档 +1
        Integer vIdx = bucketIndexOf(v.getAgeRange());
        if (Objects.nonNull(targetBucketIdx) && Objects.nonNull(vIdx))
        {
            int diff = Math.abs(vIdx - targetBucketIdx);
            if (diff == 0) { score += 3; }
            else if (diff == 1) { score += 1; }
        }
        // 中文人设标签命中 +2（只加一次）
        String charTypes = StrUtil.nullToEmpty(v.getCharacterTypes());
        for (String kw : PERSONA_KEYWORDS)
        {
            if (roleText.contains(kw) && charTypes.contains(kw))
            {
                score += 2;
                break;
            }
        }
        // 用途含「角色配音」+1
        if (StrUtil.nullToEmpty(v.getVoiceStyles()).contains("角色配音"))
        {
            score += 1;
        }
        // 去重避让：已被占用重罚，仅在无更优时才会被选中
        if (usedVoiceIds.contains(v.getId()))
        {
            score -= 1000;
        }
        return score;
    }
    /** 角色性别（男/女/无）→ 音色性别（male/female/neutral）。包含式判定，兼容手动填写的非标准值。 */
    private String mapGender(String cnGender)
    {
        if (StrUtil.isBlank(cnGender))
        {
            return VOICE_GENDER_NEUTRAL;
        }
        if (cnGender.contains("男")) { return VOICE_GENDER_MALE; }
        if (cnGender.contains("女")) { return VOICE_GENDER_FEMALE; }
        return VOICE_GENDER_NEUTRAL;
    }

    /** 归一化音色性别，非 male/female 一律视为 neutral。 */
    private String normalizeVoiceGender(String voiceGender)
    {
        if (VOICE_GENDER_MALE.equalsIgnoreCase(StrUtil.trimToEmpty(voiceGender))) { return VOICE_GENDER_MALE; }
        if (VOICE_GENDER_FEMALE.equalsIgnoreCase(StrUtil.trimToEmpty(voiceGender))) { return VOICE_GENDER_FEMALE; }
        return VOICE_GENDER_NEUTRAL;
    }

    /**
     * 从角色 age_range 解析出岁数（标准格式为纯数字「25」）。
     * 抓不到数字（中文描述 / 空）返回 null；0 也返回 null（无年龄，年龄档不参与打分）。
     */
    private Integer parseAgeNumber(String ageRange)
    {
        if (StrUtil.isBlank(ageRange))
        {
            return null;
        }
        String digits = ageRange.replaceAll("[^0-9]", "");
        if (StrUtil.isBlank(digits))
        {
            return null;
        }
        try
        {
            int age = Integer.parseInt(digits);
            return age > 0 ? age : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /** 岁数 → 年龄档序号（child=0 ... elderly=5）；null/0 返回 null。 */
    private Integer ageBucketIndex(Integer age)
    {
        if (Objects.isNull(age) || age <= 0)
        {
            return null;
        }
        if (age <= 12) { return 0; }   // child
        if (age <= 17) { return 1; }   // teen
        if (age <= 30) { return 2; }   // young
        if (age <= 45) { return 3; }   // adult
        if (age <= 60) { return 4; }   // middle
        return 5;                      // elderly
    }

    /** 音色年龄档枚举 → 序号；无法识别返回 null。 */
    private Integer bucketIndexOf(String voiceAgeRange)
    {
        if (StrUtil.isBlank(voiceAgeRange))
        {
            return null;
        }
        int idx = AGE_BUCKET_ORDER.indexOf(voiceAgeRange.trim().toLowerCase());
        return idx < 0 ? null : idx;
    }
    /** 查 asset_id 下的活跃绑定行；没有返回 null。 */
    private AidRoleVoiceBinding findActiveBinding(Long assetId)
    {
        return bindingService.getOne(Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                .eq(AidRoleVoiceBinding::getAssetId, assetId)
                .eq(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_NORMAL)
                .last("limit 1"), false);
    }

    /** 覆盖式绑定：有活跃绑定则 update，否则 insert，保证一个角色一条活跃绑定。 */
    @Transactional(rollbackFor = Exception.class)
    public void upsertBinding(AidRolePropScene role, AidAiVoiceLibrary voice, Long userId)
    {
        AidRoleVoiceBinding existing = findActiveBinding(role.getId());
        Date now = DateUtils.getNowDate();
        String operator = String.valueOf(userId);
        AidRoleVoiceBinding entity = Objects.nonNull(existing) ? existing : new AidRoleVoiceBinding();

        entity.setAssetId(role.getId());
        entity.setProjectId(role.getProjectId());
        entity.setEpisodeId(role.getEpisodeId());
        entity.setUserId(userId);

        // 音色关联 + 展示冗余
        entity.setVoiceLibraryId(voice.getId());
        entity.setVoiceCode(voice.getVoiceCode());
        entity.setVoiceName(voice.getVoiceName());
        entity.setAvatarUrl(voice.getAvatarUrl());
        entity.setSampleUrl(voice.getSampleUrl());
        entity.setSampleText(voice.getSampleText());
        entity.setLanguage(voice.getLanguage());
        entity.setGender(voice.getGender());
        entity.setAgeRange(voice.getAgeRange());
        entity.setModelId(voice.getModelId());
        entity.setProviderId(voice.getProviderId());

        // 自动绑定不设置 override 覆盖参数（走音色默认）
        entity.setStatus(STATUS_NORMAL);
        entity.setDelFlag(DEL_FLAG_NORMAL);
        entity.setUpdateBy(operator);
        entity.setUpdateTime(now);

        if (Objects.isNull(existing))
        {
            entity.setCreateBy(operator);
            entity.setCreateTime(now);
            bindingService.save(entity);
        }
        else
        {
            bindingService.updateById(entity);
        }
    }
}
