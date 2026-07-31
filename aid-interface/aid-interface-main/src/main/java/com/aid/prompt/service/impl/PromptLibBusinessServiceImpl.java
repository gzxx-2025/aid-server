package com.aid.prompt.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidPromptLib;
import com.aid.aid.service.IAidPromptLibService;
import com.aid.prompt.dto.PromptFileQueryRequest;
import com.aid.prompt.dto.PromptLibCreateRequest;
import com.aid.prompt.dto.PromptLibQueryRequest;
import com.aid.prompt.dto.PromptLibUpdateRequest;
import com.aid.prompt.service.IPromptLibBusinessService;
import com.aid.prompt.vo.PromptFileContentVO;
import com.aid.aid.domain.dto.PromptLibDataDTO;
import com.aid.aid.domain.dto.PromptLibQueryDTO;
import com.aid.common.config.AidAppConfig;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.StringUtils;
import com.aid.enums.AssetTypeEnum;
import com.aid.enums.AssetSourceTypeEnum;
import com.aid.enums.AudioSourceEnum;
import com.aid.enums.CommonEnableStatusEnum;
import com.aid.enums.CommonYesNoEnum;
import com.aid.enums.CreationModeEnum;
import com.aid.enums.CreationStepEnum;
import com.aid.enums.EpisodeStatusEnum;
import com.aid.enums.GenModeEnum;
import com.aid.enums.GenResultTargetEnum;
import com.aid.enums.GenTypeEnum;
import com.aid.enums.GenerateModeEnum;
import com.aid.enums.ModelTypeEnum;
import com.aid.enums.ProductTypeEnum;
import com.aid.enums.ProjectStatusEnum;
import com.aid.enums.ProjectTypeEnum;
import com.aid.enums.PromptTypeEnum;
import com.aid.enums.ScriptStatusEnum;
import com.aid.enums.ScriptTypeEnum;
import com.aid.enums.VideoStyleTypeEnum;
import com.aid.enums.AspectRatioEnum;
import com.aid.enums.MediaTypeEnum;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.io.FileUtil;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * 提示词素材库业务Service实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class PromptLibBusinessServiceImpl implements IPromptLibBusinessService
{
    @Autowired
    private IAidPromptLibService aidPromptLibService;

    /**
     * 校验promptType合法性
     */
    private void validatePromptType(String promptType)
    {
        if (StringUtils.isNotEmpty(promptType) && PromptTypeEnum.getByValue(promptType) == null) {
            log.info("提示词分类非法, promptType={}", promptType);
            throw new ServiceException("分类错误");
        }
    }

    @Override
    public List<AidPromptLib> selectPromptList(PromptLibQueryRequest request, Long userId)
    {
        LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidPromptLib::getDelFlag, "0");
        // (userId = 当前用户) OR (userId = 0 AND status = '0')
        wrapper.and(w -> w
                .eq(AidPromptLib::getUserId, userId)
                .or(o -> o.eq(AidPromptLib::getUserId, 0L).eq(AidPromptLib::getStatus, "0"))
        );
        wrapper.eq(StringUtils.isNotEmpty(request.getPromptType()), AidPromptLib::getPromptType, request.getPromptType());
        wrapper.like(StringUtils.isNotEmpty(request.getPromptName()), AidPromptLib::getPromptName, request.getPromptName());
        // 个人在前(userId非0)，官方在后(userId=0)，再按sortOrder排序
        wrapper.orderByDesc(AidPromptLib::getUserId);
        wrapper.orderByAsc(AidPromptLib::getSortOrder);
        return aidPromptLibService.list(wrapper);
    }

    @Override
    public AidPromptLib selectPromptDetail(Long id, Long userId)
    {
        LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidPromptLib::getId, id);
        wrapper.eq(AidPromptLib::getDelFlag, "0");
        AidPromptLib prompt = aidPromptLibService.getOne(wrapper);
        if (prompt == null) {
            log.info("提示词详情-记录缺失, id={}, userId={}", id, userId);
            throw new ServiceException("提示词不存在");
        }
        // 官方提示词(userId=0)所有人可查看，个人提示词需校验归属
        if (prompt.getUserId() != null && prompt.getUserId() > 0
                && !prompt.getUserId().equals(userId)) {
            log.info("提示词详情-归属校验失败, id={}, ownerId={}, userId={}", id, prompt.getUserId(), userId);
            throw new ServiceException("提示词不存在");
        }
        return prompt;
    }

    @Override
    public AidPromptLib createPrompt(PromptLibCreateRequest request, Long userId)
    {
        // 校验promptType
        validatePromptType(request.getPromptType());

        AidPromptLib prompt = new AidPromptLib();
        prompt.setUserId(userId);
        prompt.setPromptType(request.getPromptType());
        prompt.setPromptName(request.getPromptName());
        prompt.setPromptContent(request.getPromptContent());
        prompt.setCoverUrl(request.getCoverUrl());
        prompt.setRemark(request.getRemark());
        prompt.setSortOrder(0L);
        prompt.setStatus("0");
        prompt.setDelFlag("0");
        prompt.setCreateBy(String.valueOf(userId));
        prompt.setCreateTime(DateUtils.getNowDate());
        aidPromptLibService.save(prompt);
        return prompt;
    }

    @Override
    public AidPromptLib updatePrompt(PromptLibUpdateRequest request, Long userId)
    {
        LambdaQueryWrapper<AidPromptLib> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(AidPromptLib::getId, request.getId());
        queryWrapper.eq(AidPromptLib::getDelFlag, "0");
        AidPromptLib prompt = aidPromptLibService.getOne(queryWrapper);
        if (prompt == null) {
            log.info("提示词修改-记录缺失, id={}, userId={}", request.getId(), userId);
            throw new ServiceException("提示词不存在");
        }
        if (prompt.getUserId() != null && prompt.getUserId() == 0L) {
            log.info("提示词修改-拒绝操作官方提示词, id={}, userId={}", request.getId(), userId);
            throw new ServiceException("无权操作");
        }
        if (!prompt.getUserId().equals(userId)) {
            log.info("提示词修改-归属校验失败, id={}, ownerId={}, userId={}", request.getId(), prompt.getUserId(), userId);
            throw new ServiceException("提示词不存在");
        }
        // 仅允许修改以下字段
        LambdaUpdateWrapper<AidPromptLib> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidPromptLib::getId, request.getId());
        updateWrapper.eq(AidPromptLib::getUserId, userId);
        updateWrapper.eq(AidPromptLib::getDelFlag, "0");
        if (request.getPromptName() != null) {
            if (StringUtils.isEmpty(request.getPromptName()) || request.getPromptName().length() > 100) {
                log.info("提示词修改-名称非法, id={}, length={}", request.getId(),
                        request.getPromptName() == null ? 0 : request.getPromptName().length());
                throw new ServiceException("名称超长");
            }
            updateWrapper.set(AidPromptLib::getPromptName, request.getPromptName());
        }
        if (request.getPromptContent() != null) {
            updateWrapper.set(AidPromptLib::getPromptContent, request.getPromptContent());
        }
        if (request.getCoverUrl() != null) {
            updateWrapper.set(AidPromptLib::getCoverUrl, request.getCoverUrl());
        }
        if (request.getRemark() != null) {
            updateWrapper.set(AidPromptLib::getRemark, request.getRemark());
        }
        updateWrapper.set(AidPromptLib::getUpdateBy, String.valueOf(userId));
        updateWrapper.set(AidPromptLib::getUpdateTime, DateUtils.getNowDate());
        aidPromptLibService.update(updateWrapper);
        return aidPromptLibService.getById(request.getId());
    }

    @Override
    public int deletePrompt(Long id, Long userId)
    {
        LambdaQueryWrapper<AidPromptLib> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(AidPromptLib::getId, id);
        queryWrapper.eq(AidPromptLib::getDelFlag, "0");
        AidPromptLib prompt = aidPromptLibService.getOne(queryWrapper);
        if (prompt == null) {
            log.info("提示词删除-记录缺失, id={}, userId={}", id, userId);
            throw new ServiceException("提示词不存在");
        }
        if (prompt.getUserId() != null && prompt.getUserId() == 0L) {
            log.info("提示词删除-拒绝操作官方提示词, id={}, userId={}", id, userId);
            throw new ServiceException("无权操作");
        }
        if (!prompt.getUserId().equals(userId)) {
            log.info("提示词删除-归属校验失败, id={}, ownerId={}, userId={}", id, prompt.getUserId(), userId);
            throw new ServiceException("提示词不存在");
        }
        // 软删除
        LambdaUpdateWrapper<AidPromptLib> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidPromptLib::getId, id);
        updateWrapper.eq(AidPromptLib::getUserId, userId);
        updateWrapper.set(AidPromptLib::getDelFlag, "1");
        updateWrapper.set(AidPromptLib::getUpdateBy, String.valueOf(userId));
        updateWrapper.set(AidPromptLib::getUpdateTime, DateUtils.getNowDate());
        return aidPromptLibService.update(updateWrapper) ? 1 : 0;
    }

    /**
     * 获取统一字典数据
     * 提供系统中的提示词库数据和枚举数据
     *
     * @param queryDTO 查询条件
     * @return 统一字典数据
     */
    @Override
    public PromptLibDataDTO getPromptLibData(PromptLibQueryDTO queryDTO) {
        PromptLibDataDTO result = new PromptLibDataDTO();
        List<PromptLibDataDTO.PromptLibItem> promptLibItems = new ArrayList<>();
        List<PromptLibDataDTO.EnumItem> enumItems = new ArrayList<>();

        String category = queryDTO != null ? queryDTO.getCategory() : null;

        // 未指定分类或指定为 all 时返回全部数据，否则按枚举类型/提示词分类分流查询
        if (StrUtil.isBlank(category) || "all".equalsIgnoreCase(category)) {
            promptLibItems = queryPromptLibData(null, queryDTO);
            enumItems = queryEnumData(null);
        } else {
            if (isEnumType(category)) {
                enumItems = queryEnumData(category);
            } else {
                promptLibItems = queryPromptLibData(category, queryDTO);
            }
        }

        result.setPromptLibList(promptLibItems);
        result.setEnumList(enumItems);

        log.info("查询字典数据成功，分类：{}，提示词数量：{}，枚举数量：{}",
                category, promptLibItems.size(), enumItems.size());
        return result;
    }

    /**
     * 判断是否为枚举类型
     *
     * @param type 类型名称
     * @return true=枚举类型，false=提示词分类
     */
    private boolean isEnumType(String type) {
        if (StrUtil.isBlank(type)) {
            return false;
        }
        // 以 Enum 结尾的视为枚举类型
        return type.endsWith("Enum");
    }

    /**
     * 查询提示词库数据
     *
     * @param promptType 提示词分类（可选）
     * @param queryDTO 查询条件
     * @return 提示词库数据列表
     */
    private List<PromptLibDataDTO.PromptLibItem> queryPromptLibData(String promptType, PromptLibQueryDTO queryDTO) {
        LambdaQueryWrapper<AidPromptLib> queryWrapper = Wrappers.lambdaQuery();

        // 仅返回白名单 11 类官方参数词库：非白名单分类不返回、不暴露用户私有记录、status 仅限正常
        if (StrUtil.isNotBlank(promptType)) {
            if (!com.aid.prompt.constant.OfficialPromptCategory.isAllowed(promptType)) {
                // 非白名单分类直接返回空
                return java.util.Collections.emptyList();
            }
            queryWrapper.eq(AidPromptLib::getPromptType, promptType);
        } else {
            queryWrapper.in(AidPromptLib::getPromptType, com.aid.prompt.constant.OfficialPromptCategory.codes());
        }

        // 仅取官方、正常状态、未删除记录
        queryWrapper.eq(AidPromptLib::getUserId, 0L);
        queryWrapper.eq(AidPromptLib::getStatus, "0");
        queryWrapper.eq(AidPromptLib::getDelFlag, "0");
        queryWrapper.orderByAsc(AidPromptLib::getSortOrder);

        List<AidPromptLib> promptLibList = aidPromptLibService.list(queryWrapper);

        return promptLibList.stream()
                .map(lib -> {
                    PromptLibDataDTO.PromptLibItem item = new PromptLibDataDTO.PromptLibItem();
                    item.setId(lib.getId());
                    item.setPromptType(lib.getPromptType());
                    item.setPromptName(lib.getPromptName());
                    item.setPromptContent(lib.getPromptContent());
                    item.setCoverUrl(lib.getCoverUrl());
                    item.setSortOrder(lib.getSortOrder());
                    item.setStatus(lib.getStatus());
                    return item;
                })
                .collect(Collectors.toList());
    }

    /**
     * 查询枚举数据
     *
     * @param enumType 枚举类型（可选）
     * @return 枚举数据列表
     */
    private List<PromptLibDataDTO.EnumItem> queryEnumData(String enumType) {
        List<PromptLibDataDTO.EnumItem> enumItems = new ArrayList<>();

        // 如果指定了枚举类型，只返回该类型的枚举
        if (StrUtil.isNotBlank(enumType)) {
            switch (enumType) {
                case "PromptTypeEnum":
                    enumItems.addAll(convertToEnumItem(PromptTypeEnum.values(), "提示词分类"));
                    break;
                case "ModelTypeEnum":
                    enumItems.addAll(convertToEnumItem(ModelTypeEnum.values(), "AI模型分类"));
                    break;
                case "GenerateModeEnum":
                    enumItems.addAll(convertToEnumItem(GenerateModeEnum.values(), "AI模型生成模式"));
                    break;
                case "GenTypeEnum":
                    enumItems.addAll(convertToEnumItem(GenTypeEnum.values(), "生成类型"));
                    break;
                case "CreationModeEnum":
                    enumItems.addAll(convertToEnumItem(CreationModeEnum.values(), "创作模式"));
                    break;
                case "ProjectStatusEnum":
                    enumItems.addAll(convertToEnumItem(ProjectStatusEnum.values(), "项目状态"));
                    break;
                case "EpisodeStatusEnum":
                    enumItems.addAll(convertToEnumItem(EpisodeStatusEnum.values(), "剧集状态"));
                    break;
                case "ScriptStatusEnum":
                    enumItems.addAll(convertToEnumItem(ScriptStatusEnum.values(), "脚本状态"));
                    break;
                case "ScriptTypeEnum":
                    enumItems.addAll(convertToEnumItem(ScriptTypeEnum.values(), "脚本类型"));
                    break;
                case "VideoStyleTypeEnum":
                    enumItems.addAll(convertToEnumItem(VideoStyleTypeEnum.values(), "视频风格类型"));
                    break;
                case "GenModeEnum":
                    enumItems.addAll(convertToEnumItem(GenModeEnum.values(), "生成模式"));
                    break;
                case "ProjectTypeEnum":
                    enumItems.addAll(convertToEnumItem(ProjectTypeEnum.values(), "项目类型"));
                    break;
                case "AssetSourceTypeEnum":
                    enumItems.addAll(convertToEnumItem(AssetSourceTypeEnum.values(), "素材来源类型"));
                    break;
                case "AssetTypeEnum":
                    enumItems.addAll(convertToEnumItem(AssetTypeEnum.values(), "素材类型"));
                    break;
                case "AudioSourceEnum":
                    enumItems.addAll(convertToEnumItem(AudioSourceEnum.values(), "音频来源"));
                    break;
                case "MediaTypeEnum":
                    enumItems.addAll(convertToEnumItem(MediaTypeEnum.values(), "媒体类型"));
                    break;
                case "ProductTypeEnum":
                    enumItems.addAll(convertToEnumItem(ProductTypeEnum.values(), "产品类型"));
                    break;
                case "AspectRatioEnum":
                    enumItems.addAll(convertToEnumItem(AspectRatioEnum.values(), "宽高比"));
                    break;
                case "CreationStepEnum":
                    enumItems.addAll(convertToEnumItem(CreationStepEnum.values(), "创作步骤"));
                    break;
                case "GenResultTargetEnum":
                    enumItems.addAll(convertToEnumItem(GenResultTargetEnum.values(), "生成结果目标"));
                    break;
                case "CommonEnableStatusEnum":
                    enumItems.addAll(convertToEnumItem(CommonEnableStatusEnum.values(), "通用启用状态"));
                    break;
                case "CommonYesNoEnum":
                    enumItems.addAll(convertToEnumItem(CommonYesNoEnum.values(), "通用是否"));
                    break;
                default:
                    log.warn("未知的枚举类型：{}", enumType);
            }
        } else {
            // 返回所有常用枚举
            enumItems.addAll(convertToEnumItem(PromptTypeEnum.values(), "提示词分类"));
            enumItems.addAll(convertToEnumItem(ModelTypeEnum.values(), "AI模型分类"));
            enumItems.addAll(convertToEnumItem(GenerateModeEnum.values(), "AI模型生成模式"));
            enumItems.addAll(convertToEnumItem(GenTypeEnum.values(), "生成类型"));
            enumItems.addAll(convertToEnumItem(CreationModeEnum.values(), "创作模式"));
            enumItems.addAll(convertToEnumItem(ProjectStatusEnum.values(), "项目状态"));
            enumItems.addAll(convertToEnumItem(EpisodeStatusEnum.values(), "剧集状态"));
            enumItems.addAll(convertToEnumItem(ScriptStatusEnum.values(), "脚本状态"));
            enumItems.addAll(convertToEnumItem(ScriptTypeEnum.values(), "脚本类型"));
            enumItems.addAll(convertToEnumItem(VideoStyleTypeEnum.values(), "视频风格类型"));
            enumItems.addAll(convertToEnumItem(GenModeEnum.values(), "生成模式"));
            enumItems.addAll(convertToEnumItem(ProjectTypeEnum.values(), "项目类型"));
            enumItems.addAll(convertToEnumItem(AssetSourceTypeEnum.values(), "素材来源类型"));
            enumItems.addAll(convertToEnumItem(AssetTypeEnum.values(), "素材类型"));
            enumItems.addAll(convertToEnumItem(AudioSourceEnum.values(), "音频来源"));
            enumItems.addAll(convertToEnumItem(MediaTypeEnum.values(), "媒体类型"));
            enumItems.addAll(convertToEnumItem(ProductTypeEnum.values(), "产品类型"));
            enumItems.addAll(convertToEnumItem(AspectRatioEnum.values(), "宽高比"));
            enumItems.addAll(convertToEnumItem(CreationStepEnum.values(), "创作步骤"));
            enumItems.addAll(convertToEnumItem(GenResultTargetEnum.values(), "生成结果目标"));
            enumItems.addAll(convertToEnumItem(CommonEnableStatusEnum.values(), "通用启用状态"));
            enumItems.addAll(convertToEnumItem(CommonYesNoEnum.values(), "通用是否"));
        }

        return enumItems;
    }

    /**
     * 将枚举数组转换为枚举项列表
     *
     * @param enums 枚举数组
     * @param category 枚举分类
     * @return 枚举项列表
     */
    private <T extends Enum<T>> List<PromptLibDataDTO.EnumItem> convertToEnumItem(T[] enums, String category) {
        List<PromptLibDataDTO.EnumItem> items = new ArrayList<>();
        for (T enumItem : enums) {
            try {
                // 使用反射获取value和desc
                java.lang.reflect.Field valueField = enumItem.getClass().getDeclaredField("value");
                valueField.setAccessible(true);
                Object valueObj = valueField.get(enumItem);
                String value = String.valueOf(valueObj);

                java.lang.reflect.Field descField = enumItem.getClass().getDeclaredField("desc");
                descField.setAccessible(true);
                String desc = (String) descField.get(enumItem);

                PromptLibDataDTO.EnumItem item = new PromptLibDataDTO.EnumItem();
                item.setEnumType(enumItem.getClass().getSimpleName());
                item.setValue(value);
                item.setDesc(desc);
                item.setCategory(category);
                items.add(item);
            } catch (Exception e) {
                log.error("转换枚举项失败：{}", enumItem.name(), e);
            }
        }
        return items;
    }

    /** 允许的promptType白名单 */
    private static final Set<String> ALLOWED_PROMPT_TYPES = new HashSet<>(Arrays.asList(
            "main_business_prompt", "main_teacher_prompt"
    ));

    /** lib/prompts 目录名 */
    private static final String PROMPT_LIB_DIR = "lib/prompts";

    /**
     * 获取提示词文件内容
     * 优先从本地 lib/prompts 目录读取缓存文件，若不存在则从数据库查询后写入文件
     *
     * @param request 查询请求（promptType、remark、lang）
     * @return 中文和英文提示词内容
     */
    @Override
    public PromptFileContentVO getPromptFileContent(PromptFileQueryRequest request) {
        String promptType = request.getPromptType();
        String remark = request.getRemark();
        String lang = request.getLang();

        // 分类仅支持 main_business_prompt 和 main_teacher_prompt
        if (!ALLOWED_PROMPT_TYPES.contains(promptType)) {
            throw new RuntimeException("分类不正确");
        }

        // lang 不传或传 zh/en
        if (StrUtil.isNotBlank(lang) && !"zh".equals(lang) && !"en".equals(lang)) {
            throw new RuntimeException("不支持的语言");
        }

        String basePath = AidAppConfig.getProfile() + "/" + PROMPT_LIB_DIR;
        File zhFile = new File(basePath + "/" + remark + "_zh.txt");
        File enFile = new File(basePath + "/" + remark + "_en.txt");

        String zhContent = null;
        String enContent = null;

        // 根据 lang 决定需要读取哪些文件
        boolean needZh = StrUtil.isBlank(lang) || "zh".equals(lang);
        boolean needEn = StrUtil.isBlank(lang) || "en".equals(lang);
        boolean zhExists = zhFile.exists();
        boolean enExists = enFile.exists();

        // 所需文件均已缓存，直接读取
        if ((!needZh || zhExists) && (!needEn || enExists)) {
            log.info("提示词文件已存在，直接读取");
            if (needZh) {
                zhContent = FileUtil.readString(zhFile, StandardCharsets.UTF_8);
            }
            if (needEn) {
                enContent = FileUtil.readString(enFile, StandardCharsets.UTF_8);
            }
            return PromptFileContentVO.builder().zhContent(zhContent).enContent(enContent).build();
        }

        // 文件不存在，从数据库查询并落地为文件缓存
        log.info("提示词文件不存在，从数据库查询并写入文件: promptType={}, remark={}", promptType, remark);

        LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidPromptLib::getPromptType, promptType);
        wrapper.eq(AidPromptLib::getRemark, remark);
        wrapper.eq(AidPromptLib::getDelFlag, "0");
        wrapper.last("LIMIT 1");

        AidPromptLib promptLib = aidPromptLibService.getOne(wrapper);
        if (promptLib == null) {
            throw new RuntimeException("未找到对应的提示词数据");
        }

        FileUtil.mkdir(basePath);

        // 中英文内容各自写入缓存文件
        if (StrUtil.isNotBlank(promptLib.getPromptContent())) {
            FileUtil.writeString(promptLib.getPromptContent(), zhFile, StandardCharsets.UTF_8);
            log.info("已写入中文提示词文件: {}", zhFile.getAbsolutePath());
        }
        if (StrUtil.isNotBlank(promptLib.getPromptContentEn())) {
            FileUtil.writeString(promptLib.getPromptContentEn(), enFile, StandardCharsets.UTF_8);
            log.info("已写入英文提示词文件: {}", enFile.getAbsolutePath());
        }

        // 按 lang 返回对应内容
        if (needZh) {
            zhContent = promptLib.getPromptContent();
        }
        if (needEn) {
            enContent = promptLib.getPromptContentEn();
        }
        return PromptFileContentVO.builder().zhContent(zhContent).enContent(enContent).build();
    }
}
