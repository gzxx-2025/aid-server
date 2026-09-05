package com.aid.project.service;

import java.util.Locale;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aid.aid.domain.AidComicAsset;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidUserComicAsset;
import com.aid.aid.service.IAidComicAssetService;
import com.aid.aid.service.IAidUserComicAssetService;
import com.aid.aid.util.HiddenStylePromptJsonUtils;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 项目风格源解析与隐藏提示词快照服务。
 */
@Slf4j
@Service
public class ProjectStyleSnapshotService
{
    public static final String SOURCE_OFFICIAL = "official";

    public static final String SOURCE_CUSTOM = "custom";

    private static final String ASSET_TYPE_STYLE = "style";

    private static final String NORMAL_FLAG = "0";

    @Autowired
    private IAidComicAssetService comicAssetService;

    @Autowired
    private IAidUserComicAssetService userComicAssetService;

    /**
     * 按来源和主键读取有效风格。隐藏字段只在服务端读取，不进入 C 端 VO。
     */
    public ResolvedProjectStyle resolve(String styleSource, Long styleAssetId, Long userId)
    {
        if (StrUtil.isBlank(styleSource) || Objects.isNull(styleAssetId))
        {
            log.info("解析项目风格失败，来源或ID为空: source={}, assetId={}, userId={}",
                    styleSource, styleAssetId, userId);
            throw new ServiceException("请选择风格");
        }
        String normalizedSource = styleSource.trim().toLowerCase(Locale.ROOT);
        if (Objects.equals(SOURCE_OFFICIAL, normalizedSource))
        {
            return resolveOfficial(styleAssetId);
        }
        if (Objects.equals(SOURCE_CUSTOM, normalizedSource))
        {
            return resolveCustom(styleAssetId, userId);
        }
        log.info("解析项目风格失败，来源非法: source={}, assetId={}, userId={}",
                styleSource, styleAssetId, userId);
        throw new ServiceException("风格来源错误");
    }

    /**
     * 角色形态图和角色设定卡使用 character 隐藏模板，异常时回退公开风格描述。
     */
    public String resolveCharacterPrompt(AidComicProject project)
    {
        if (Objects.isNull(project))
        {
            return "";
        }
        return HiddenStylePromptJsonUtils.resolve(project.getHiddenStylePromptJson(),
                HiddenStylePromptJsonUtils.KEY_CHARACTER, project.getVideoStyleValue());
    }

    private ResolvedProjectStyle resolveOfficial(Long styleAssetId)
    {
        // 查询字段精简：项目快照仅需名称、公开提示词与隐藏模板。
        AidComicAsset asset = comicAssetService.getOne(Wrappers.<AidComicAsset>lambdaQuery()
                .select(AidComicAsset::getId, AidComicAsset::getAssetName,
                        AidComicAsset::getPromptText, AidComicAsset::getHiddenStylePromptJson)
                .eq(AidComicAsset::getId, styleAssetId)
                .eq(AidComicAsset::getAssetType, ASSET_TYPE_STYLE)
                .eq(AidComicAsset::getDelFlag, NORMAL_FLAG)
                .last("LIMIT 1"), false);
        if (Objects.isNull(asset) || StrUtil.isBlank(asset.getAssetName()) || StrUtil.isBlank(asset.getPromptText()))
        {
            log.info("官方风格无效: assetId={}", styleAssetId);
            throw new ServiceException("风格不可用");
        }
        return new ResolvedProjectStyle(asset.getAssetName(), asset.getPromptText(),
                normalizeStoredJson(asset.getHiddenStylePromptJson(), styleAssetId, SOURCE_OFFICIAL));
    }

    private ResolvedProjectStyle resolveCustom(Long styleAssetId, Long userId)
    {
        // 查询字段精简：同时校验用户归属、类型、状态与删除标记。
        AidUserComicAsset asset = userComicAssetService.getOne(Wrappers.<AidUserComicAsset>lambdaQuery()
                .select(AidUserComicAsset::getId, AidUserComicAsset::getAssetName,
                        AidUserComicAsset::getPromptText, AidUserComicAsset::getHiddenStylePromptJson)
                .eq(AidUserComicAsset::getId, styleAssetId)
                .eq(AidUserComicAsset::getUserId, userId)
                .eq(AidUserComicAsset::getAssetType, ASSET_TYPE_STYLE)
                .eq(AidUserComicAsset::getStatus, NORMAL_FLAG)
                .eq(AidUserComicAsset::getDelFlag, NORMAL_FLAG)
                .last("LIMIT 1"), false);
        if (Objects.isNull(asset) || StrUtil.isBlank(asset.getAssetName()) || StrUtil.isBlank(asset.getPromptText()))
        {
            log.info("自定义风格无效或无权访问: assetId={}, userId={}", styleAssetId, userId);
            throw new ServiceException("风格不可用");
        }
        String hiddenJson = asset.getHiddenStylePromptJson();
        if (StrUtil.isBlank(hiddenJson))
        {
            // 兼容迁移前创建的自定义风格，项目快照仍按其公开提示词生成一期角色模板。
            hiddenJson = HiddenStylePromptJsonUtils.fromCharacterPrompt(asset.getPromptText());
        }
        return new ResolvedProjectStyle(asset.getAssetName(), asset.getPromptText(),
                normalizeStoredJson(hiddenJson, styleAssetId, SOURCE_CUSTOM));
    }

    private String normalizeStoredJson(String json, Long styleAssetId, String source)
    {
        if (StrUtil.isBlank(json))
        {
            return null;
        }
        try
        {
            return HiddenStylePromptJsonUtils.normalize(json);
        }
        catch (IllegalArgumentException e)
        {
            log.error("风格隐藏模板格式错误: source={}, assetId={}", source, styleAssetId, e);
            throw new ServiceException("风格配置错误");
        }
    }

    /**
     * 服务端解析后的风格快照内容。
     */
    public record ResolvedProjectStyle(String styleName, String publicPrompt, String hiddenPromptJson)
    {
    }
}
