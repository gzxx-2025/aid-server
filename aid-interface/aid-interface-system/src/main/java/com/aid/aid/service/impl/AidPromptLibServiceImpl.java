package com.aid.aid.service.impl;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.common.config.AidAppConfig;
import com.aid.common.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.mapper.AidPromptLibMapper;
import com.aid.aid.domain.AidPromptLib;
import com.aid.aid.domain.dto.SystemPromptUpdateRequest;
import com.aid.aid.domain.vo.PromptVersionCheckVO;
import com.aid.aid.domain.vo.PromptVersionItemVO;
import com.aid.aid.service.IAidPromptLibService;

/**
 * 提示词素材库(官方预设与用户自定义)Service业务层处理
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AidPromptLibServiceImpl extends ServiceImpl<AidPromptLibMapper, AidPromptLib> implements IAidPromptLibService
{
    @Autowired
    private AidPromptLibMapper aidPromptLibMapper;

    /** 允许的promptType白名单 */
    private static final Set<String> ALLOWED_PROMPT_TYPES = new HashSet<>(Arrays.asList(
            "main_business_prompt", "main_teacher_prompt"
    ));

    /** lib/prompts 目录名 */
    private static final String PROMPT_LIB_DIR = "lib/prompts";

    /**
     * 查询提示词素材库(官方预设与用户自定义)
     *
     * @param id 提示词素材库(官方预设与用户自定义)主键
     * @return 提示词素材库(官方预设与用户自定义)
     */
    @Override
    public AidPromptLib selectAidPromptLibById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询提示词素材库(官方预设与用户自定义)列表
     *
     * @param aidPromptLib 提示词素材库(官方预设与用户自定义)
     * @return 提示词素材库(官方预设与用户自定义)
     */
    @Override
    public List<AidPromptLib> selectAidPromptLibList(AidPromptLib aidPromptLib)
    {
        LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.lambdaQuery();
        if (aidPromptLib != null)
        {
            if (aidPromptLib.getUserId() != null)
            {
                wrapper.eq(AidPromptLib::getUserId, aidPromptLib.getUserId());
            }
            if (StrUtil.isNotBlank(aidPromptLib.getPromptType()))
            {
                wrapper.eq(AidPromptLib::getPromptType, aidPromptLib.getPromptType());
            }
            if (StrUtil.isNotBlank(aidPromptLib.getPromptName()))
            {
                wrapper.like(AidPromptLib::getPromptName, aidPromptLib.getPromptName());
            }
            if (StrUtil.isNotBlank(aidPromptLib.getStatus()))
            {
                wrapper.eq(AidPromptLib::getStatus, aidPromptLib.getStatus());
            }
            if (aidPromptLib.getVersion() != null)
            {
                wrapper.eq(AidPromptLib::getVersion, aidPromptLib.getVersion());
            }
            if (StrUtil.isNotBlank(aidPromptLib.getRemark()))
            {
                wrapper.like(AidPromptLib::getRemark, aidPromptLib.getRemark());
            }
        }
        wrapper.orderByAsc(AidPromptLib::getSortOrder).orderByDesc(AidPromptLib::getId);
        return this.list(wrapper);
    }

    /**
     * 新增提示词素材库(官方预设与用户自定义)
     *
     * @param aidPromptLib 提示词素材库(官方预设与用户自定义)
     * @return 结果
     */
    @Override
    public int insertAidPromptLib(AidPromptLib aidPromptLib)
    {
        aidPromptLib.setCreateTime(DateUtils.getNowDate());
        return this.save(aidPromptLib) ? 1 : 0;
    }

    /**
     * 修改提示词素材库(官方预设与用户自定义)
     *
     * @param aidPromptLib 提示词素材库(官方预设与用户自定义)
     * @return 结果
     */
    @Override
    public int updateAidPromptLib(AidPromptLib aidPromptLib)
    {
        aidPromptLib.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidPromptLib) ? 1 : 0;
    }

    /**
     * 批量删除提示词素材库(官方预设与用户自定义)
     *
     * @param ids 需要删除的提示词素材库(官方预设与用户自定义)主键
     * @return 结果
     */
    @Override
    public int deleteAidPromptLibByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除提示词素材库(官方预设与用户自定义)信息
     *
     * @param id 提示词素材库(官方预设与用户自定义)主键
     * @return 结果
     */
    @Override
    public int deleteAidPromptLibById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }

    /**
     * 查询系统提示词列表（仅 main_business_prompt / main_teacher_prompt）
     */
    @Override
    public List<AidPromptLib> selectSystemPromptList(String promptType)
    {
        LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidPromptLib::getDelFlag, "0");
        wrapper.in(AidPromptLib::getPromptType, "main_business_prompt", "main_teacher_prompt");
        if (StrUtil.isNotBlank(promptType)) {
            wrapper.eq(AidPromptLib::getPromptType, promptType);
        }
        wrapper.orderByAsc(AidPromptLib::getPromptType);
        wrapper.orderByAsc(AidPromptLib::getSortOrder);
        return this.list(wrapper);
    }

    /**
     * 修改系统提示词（仅允许修改 main_business_prompt / main_teacher_prompt 及版本号）
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public AidPromptLib updateSystemPrompt(SystemPromptUpdateRequest request)
    {
        // 查询原记录
        AidPromptLib existing = this.getById(request.getId());
        if (existing == null || "1".equals(existing.getDelFlag())) {
            throw new RuntimeException("提示词不存在");
        }
        // 校验类型
        if (!ALLOWED_PROMPT_TYPES.contains(existing.getPromptType())) {
            throw new RuntimeException("仅允许修改系统提示词");
        }

        LambdaUpdateWrapper<AidPromptLib> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidPromptLib::getId, request.getId());

        if (request.getPromptName() != null) {
            updateWrapper.set(AidPromptLib::getPromptName, request.getPromptName());
        }
        if (request.getPromptContent() != null) {
            updateWrapper.set(AidPromptLib::getPromptContent, request.getPromptContent());
        }
        if (request.getPromptContentEn() != null) {
            updateWrapper.set(AidPromptLib::getPromptContentEn, request.getPromptContentEn());
        }
        if (request.getVersion() != null) {
            updateWrapper.set(AidPromptLib::getVersion, request.getVersion());
        }
        if (request.getSortOrder() != null) {
            updateWrapper.set(AidPromptLib::getSortOrder, request.getSortOrder());
        }
        if (request.getStatus() != null) {
            updateWrapper.set(AidPromptLib::getStatus, request.getStatus());
        }
        updateWrapper.set(AidPromptLib::getUpdateTime, DateUtils.getNowDate());

        this.update(updateWrapper);

        // 重新查询最新记录，用于回写文件和返回
        AidPromptLib updated = this.getById(request.getId());

        // 更新后同步写回本地提示词文件（替代旧的"删除缓存"策略）
        String remark = existing.getRemark();
        if (StrUtil.isNotBlank(remark)) {
            String basePath = AidAppConfig.getProfile() + "/" + PROMPT_LIB_DIR;
            try {
                // 确保目录存在
                FileUtil.mkdir(basePath);
                // 写入中文提示词文件
                String zhContent = updated.getPromptContent();
                File zhFile = new File(basePath + "/" + remark + "_zh.txt");
                if (StrUtil.isNotBlank(zhContent)) {
                    FileUtil.writeString(zhContent, zhFile, StandardCharsets.UTF_8);
                } else {
                    // 内容为空时也写入空文件，保持文件与数据库一致
                    FileUtil.writeString("", zhFile, StandardCharsets.UTF_8);
                }
                // 写入英文提示词文件
                String enContent = updated.getPromptContentEn();
                File enFile = new File(basePath + "/" + remark + "_en.txt");
                if (StrUtil.isNotBlank(enContent)) {
                    FileUtil.writeString(enContent, enFile, StandardCharsets.UTF_8);
                } else {
                    FileUtil.writeString("", enFile, StandardCharsets.UTF_8);
                }
                log.info("提示词文件同步成功: remark={}, zhPath={}, enPath={}",
                        remark, zhFile.getAbsolutePath(), enFile.getAbsolutePath());
            } catch (Exception e) {
                log.error("提示词文件同步失败: remark={}, error={}", remark, e.getMessage(), e);
                throw new RuntimeException("文件同步失败");
            }
        }

        return updated;
    }

    /**
     * 模拟远程版本数据（key=remark, value=最新版本号）
     * TODO: 后续替换为真实外部接口调用
     */
    private static final Map<String, Integer> REMOTE_VERSION_MAP = new HashMap<>();
    static {
        // main_business_prompt
        REMOTE_VERSION_MAP.put("aid_persona_blueprint", 200);
        REMOTE_VERSION_MAP.put("aid_vision_to_persona", 150);
        REMOTE_VERSION_MAP.put("aid_dubbing_analyzer", 120);
        REMOTE_VERSION_MAP.put("aid_shot_editor", 180);
        REMOTE_VERSION_MAP.put("aid_shot_renderer", 160);
        REMOTE_VERSION_MAP.put("aid_prop_extractor", 110);
        REMOTE_VERSION_MAP.put("aid_scene_extractor", 130);
        REMOTE_VERSION_MAP.put("aid_script_formatter", 140);
        REMOTE_VERSION_MAP.put("aid_prop_updater", 170);
        REMOTE_VERSION_MAP.put("aid_scene_remaker", 190);
        REMOTE_VERSION_MAP.put("aid_scene_modifier", 100);
        REMOTE_VERSION_MAP.put("aid_scene_updater", 115);
        REMOTE_VERSION_MAP.put("aid_scene_builder", 125);
        REMOTE_VERSION_MAP.put("aid_prompt_tuner", 135);
        REMOTE_VERSION_MAP.put("aid_drama_chunker", 145);
        REMOTE_VERSION_MAP.put("aid_persona_remaker", 155);
        REMOTE_VERSION_MAP.put("aid_persona_modifier", 165);
        REMOTE_VERSION_MAP.put("aid_persona_updater", 175);
        REMOTE_VERSION_MAP.put("aid_persona_builder", 185);
        REMOTE_VERSION_MAP.put("aid_script_expander", 195);
        REMOTE_VERSION_MAP.put("aid_board_planner", 105);
        REMOTE_VERSION_MAP.put("aid_board_inserter", 108);
        REMOTE_VERSION_MAP.put("aid_board_detailer", 112);
        REMOTE_VERSION_MAP.put("aid_cam_variant_gen", 118);
        REMOTE_VERSION_MAP.put("aid_cam_variant_eval", 122);
        REMOTE_VERSION_MAP.put("aid_clip_segmenter", 128);
        REMOTE_VERSION_MAP.put("aid_dp_director", 132);
        REMOTE_VERSION_MAP.put("aid_visual_stylist", 138);
        REMOTE_VERSION_MAP.put("aid_casting_director", 142);
        REMOTE_VERSION_MAP.put("aid_acting_coach", 148);
        // main_teacher_prompt
        REMOTE_VERSION_MAP.put("aid_sys_tutorial", 300);
        REMOTE_VERSION_MAP.put("aid_sys_api_router", 250);
    }

    /**
     * 检查系统提示词版本更新状态
     */
    @Override
    public List<PromptVersionCheckVO> checkSystemPromptUpdate() {
        // 查询所有系统提示词
        List<AidPromptLib> list = this.selectSystemPromptList(null);
        List<PromptVersionCheckVO> result = new ArrayList<>();

        for (AidPromptLib item : list) {
            String remark = item.getRemark();
            Integer currentVersion = item.getVersion() != null ? item.getVersion() : 100;
            Integer remoteVersion = REMOTE_VERSION_MAP.getOrDefault(remark, currentVersion);

            String status;
            if (currentVersion.equals(remoteVersion)) {
                status = "latest";
            } else if (currentVersion < remoteVersion) {
                status = "need_update";
            } else {
                status = "version_error";
            }

            result.add(PromptVersionCheckVO.builder()
                    .remark(remark)
                    .latestVersion(remoteVersion)
                    .currentVersion(currentVersion)
                    .status(status)
                    .build());
        }
        return result;
    }

    /**
     * 根据文件名称获取提示词的历史版本列表（模拟）
     * TODO: 后续替换为真实外部接口调用
     */
    @Override
    public List<PromptVersionItemVO> getPromptVersionsByRemark(String remark) {
        Integer latestVersion = REMOTE_VERSION_MAP.getOrDefault(remark, 100);
        List<PromptVersionItemVO> versions = new ArrayList<>();

        versions.add(PromptVersionItemVO.builder()
                .version(latestVersion)
                .description("最新版本")
                .publishTime("2026-04-01 10:00:00")
                .build());

        if (latestVersion > 150) {
            versions.add(PromptVersionItemVO.builder()
                    .version(latestVersion - 50)
                    .description("上一版本")
                    .publishTime("2026-03-15 10:00:00")
                    .build());
        }
        if (latestVersion > 200) {
            versions.add(PromptVersionItemVO.builder()
                    .version(latestVersion - 100)
                    .description("更早版本")
                    .publishTime("2026-03-01 10:00:00")
                    .build());
        }

        return versions;
    }

    /**
     * 拉取系统提示词更新（模拟从远程获取最新版本并更新本地）
     * TODO: 后续替换为真实外部接口调用
     */
    @Override
    public AidPromptLib pullSystemPromptUpdate(String remark) {
        if (StrUtil.isBlank(remark)) {
            throw new RuntimeException("文件名称不能为空");
        }

        // 查询本地记录
        LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidPromptLib::getRemark, remark);
        wrapper.eq(AidPromptLib::getDelFlag, "0");
        wrapper.last("LIMIT 1");
        AidPromptLib existing = this.getOne(wrapper);
        if (existing == null) {
            throw new RuntimeException("未找到对应的提示词数据");
        }
        if (!ALLOWED_PROMPT_TYPES.contains(existing.getPromptType())) {
            throw new RuntimeException("仅允许更新系统提示词");
        }

        Integer remoteVersion = REMOTE_VERSION_MAP.get(remark);
        if (remoteVersion == null) {
            throw new RuntimeException("远程无对应版本的提示词数据");
        }

        Integer currentVersion = existing.getVersion() != null ? existing.getVersion() : 100;
        if (currentVersion.equals(remoteVersion)) {
            throw new RuntimeException("当前已是最新版本，无需更新");
        }

        // 更新本地记录 — 仅更新版本号，不修改提示词内容
        LambdaUpdateWrapper<AidPromptLib> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidPromptLib::getId, existing.getId());
        updateWrapper.set(AidPromptLib::getVersion, remoteVersion);
        updateWrapper.set(AidPromptLib::getUpdateTime, DateUtils.getNowDate());
        this.update(updateWrapper);

        // 清除本地文件缓存
        String basePath = AidAppConfig.getProfile() + "/" + PROMPT_LIB_DIR;
        new File(basePath + "/" + remark + "_zh.txt").delete();
        new File(basePath + "/" + remark + "_en.txt").delete();
        log.info("已拉取更新并清除文件缓存: remark={}, v{} -> v{}", remark, currentVersion, remoteVersion);

        return this.getById(existing.getId());
    }
}
