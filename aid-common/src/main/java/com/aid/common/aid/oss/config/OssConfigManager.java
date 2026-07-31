package com.aid.common.aid.oss.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.aid.oss.properties.OssProperties.UploadTypeLimit;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * OSS配置管理器
 * - 配置从数据库加载到内存
 * - 手动刷新机制，避免频繁查询数据库
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssConfigManager
{
    /**
     * 通用配置服务
     */
    private final ConfigService configService;

    /**
     * 内存缓存的所有配置
     */
    @Getter
    private final Map<String, String> configCache = new HashMap<>();

    /**
     * 当前使用的OSS配置
     */
    @Getter
    private OssProperties currentProperties;

    /**
     * 初始化标识
     */
    private volatile boolean initialized = false;

    /**
     * 初始化配置（首次使用时调用）
     */
    public void init()
    {
        if (!initialized)
        {
            refresh();
        }
    }

    /**
     * 刷新配置（从数据库重新加载）
     * 在配置页面点击"刷新配置"时调用
     */
    public void refresh()
    {
        log.info("刷新OSS配置...");

        // 一次性获取oss分类的所有配置
        Map<String, String> allConfig = configService.getConfigValues("oss");
        configCache.clear();
        if (!CollectionUtil.isEmpty(allConfig))
        {
            configCache.putAll(allConfig);
        }

        // 构建OssProperties对象
        currentProperties = buildOssProperties();

        initialized = true;
        log.info("OSS配置刷新完成: enabled={}", currentProperties.getEnabled());
    }

    /**
     * 获取OSS配置
     *
     * @return OSS配置属性
     */
    public OssProperties getOssProperties()
    {
        init();
        return currentProperties;
    }

    /**
     * 判断是否启用
     *
     * @return true=启用，false=未启用
     */
    public boolean isEnabled()
    {
        init();
        return Boolean.parseBoolean(getCacheValue("enabled", "false"));
    }

    /**
     * 获取当前生效的配置（供前端展示，脱敏处理）
     * - local 模式：仅展示 enabled / uploadMode / localDomain / maxFileSize / allowedExtensions
     * - oss 模式：展示全部配置，accessKeyId/accessKeySecret 脱敏
     *
     * @return 脱敏后的配置Map
     */
    public Map<String, String> getCurrentConfig()
    {
        init();
        Map<String, String> result = new HashMap<>(configCache);
        // 脱敏：accessKeyId
        desensitize(result, "accessKeyId");
        // 脱敏：accessKeySecret
        desensitize(result, "accessKeySecret");
        // 脱敏：腾讯云COS SecretId / SecretKey
        desensitize(result, "cosSecretId");
        desensitize(result, "cosSecretKey");
        // 根据 uploadMode 过滤展示字段
        String mode = Objects.isNull(currentProperties) ? "oss" : currentProperties.getUploadMode();
        if ("local".equalsIgnoreCase(mode))
        {
            // local 模式隐藏与 OSS / COS 相关的字段
            result.remove("endpoint");
            result.remove("accessKeyId");
            result.remove("accessKeySecret");
            result.remove("bucketName");
            result.remove("prefix");
            result.remove("cdnDomain");
            result.remove("cosRegion");
            result.remove("cosSecretId");
            result.remove("cosSecretKey");
            result.remove("cosBucketName");
            result.remove("cosPrefix");
            result.remove("cosCdnDomain");
        }
        else if ("cos".equalsIgnoreCase(mode))
        {
            // cos 模式隐藏阿里云OSS 专属字段
            result.remove("endpoint");
            result.remove("accessKeyId");
            result.remove("accessKeySecret");
            result.remove("bucketName");
            result.remove("prefix");
            result.remove("cdnDomain");
        }
        else
        {
            // oss 模式隐藏腾讯云COS 专属字段
            result.remove("cosRegion");
            result.remove("cosSecretId");
            result.remove("cosSecretKey");
            result.remove("cosBucketName");
            result.remove("cosPrefix");
            result.remove("cosCdnDomain");
        }
        return result;
    }

    /**
     * 获取缓存值
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    private String getCacheValue(String key, String defaultValue)
    {
        String value = configCache.get(key);
        return Objects.isNull(value) ? defaultValue : value;
    }

    /**
     * 获取缓存布尔值
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 布尔值
     */
    private boolean getCacheBoolean(String key, boolean defaultValue)
    {
        String value = configCache.get(key);
        if (StrUtil.isBlank(value))
        {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * 获取缓存Long值
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return Long值
     */
    private long getCacheLong(String key, long defaultValue)
    {
        String value = configCache.get(key);
        if (StrUtil.isBlank(value))
        {
            return defaultValue;
        }
        try
        {
            return Long.parseLong(value);
        }
        catch (NumberFormatException e)
        {
            log.warn("OSS配置项{}的值{}无法转换为数字，使用默认值{}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 构建OssProperties对象
     *
     * @return OSS配置属性
     */
    private OssProperties buildOssProperties()
    {
        OssProperties properties = new OssProperties();
        properties.setEnabled(getCacheBoolean("enabled", false));
        // 上传模式：trim 后再判定，缺省或非法值按 oss 处理，防止 " local " 这类带空格的脏数据被误判
        String mode = StrUtil.trim(getCacheValue("uploadMode", "oss"));
        if (StrUtil.isBlank(mode)
                || (!"local".equalsIgnoreCase(mode)
                && !"oss".equalsIgnoreCase(mode)
                && !"cos".equalsIgnoreCase(mode)))
        {
            mode = "oss";
        }
        properties.setUploadMode(mode.toLowerCase());
        properties.setEndpoint(getCacheValue("endpoint", ""));
        properties.setAccessKeyId(getCacheValue("accessKeyId", ""));
        properties.setAccessKeySecret(getCacheValue("accessKeySecret", ""));
        properties.setBucketName(getCacheValue("bucketName", ""));
        properties.setPrefix(getCacheValue("prefix", ""));
        properties.setCdnDomain(getCacheValue("cdnDomain", ""));
        // 腾讯云COS 专属配置
        properties.setCosRegion(getCacheValue("cosRegion", ""));
        properties.setCosSecretId(getCacheValue("cosSecretId", ""));
        properties.setCosSecretKey(getCacheValue("cosSecretKey", ""));
        properties.setCosBucketName(getCacheValue("cosBucketName", ""));
        properties.setCosPrefix(getCacheValue("cosPrefix", ""));
        properties.setCosCdnDomain(getCacheValue("cosCdnDomain", ""));
        properties.setLocalDomain(getCacheValue("localDomain", ""));
        // 图片URL域名白名单（逗号分隔，可空）：cdnDomain/localDomain 之外额外放行的可信外部图片域名前缀
        properties.setImageUrlWhitelist(getCacheValue("imageUrlWhitelist", ""));
        properties.setMaxFileSize(getCacheLong("maxFileSize", 5 * 1024 * 1024L));
        properties.setAllowedExtensions(getCacheValue("allowedExtensions", "jpg,jpeg,png,gif,bmp,webp"));
        // 批量上传最大文件数量：默认3，非法值/缺省回退到3
        properties.setMaxBatchCount((int) getCacheLong("maxBatchCount", 3L));
        // 分类型上传大小限制（oss 分类下单个 JSON 字段 uploadTypeLimits，后台表单维护，无需手写 JSON）
        properties.setUploadTypeLimits(buildUploadTypeLimits());
        return properties;
    }

    /** 分类型上传限制配置项名（与 oss 同分类，值为 JSON 数组字符串） */
    private static final String UPLOAD_TYPE_LIMITS_KEY = "uploadTypeLimits";

    /**
     * 解析 oss.uploadTypeLimits（JSON 数组）为分类型上传限制列表。
     * JSON 单元素：{@code {"name":"图片","maxSizeMb":10,"extensions":["jpg","png"]}}，
     * extensions 也兼容逗号分隔字符串。空 / 非法 JSON 返回空列表（上传校验自动回退全局限制）。
     *
     * @return 分类型上传限制列表（不为 null）
     */
    private List<UploadTypeLimit> buildUploadTypeLimits()
    {
        List<UploadTypeLimit> result = new ArrayList<>();
        String json = getCacheValue(UPLOAD_TYPE_LIMITS_KEY, "");
        if (StrUtil.isBlank(json))
        {
            return result;
        }
        JSONArray arr;
        try
        {
            arr = JSON.parseArray(json);
        }
        catch (Exception e)
        {
            log.warn("解析 oss.uploadTypeLimits 失败，回退全局上传限制: {}", e.getMessage());
            return result;
        }
        if (CollectionUtil.isEmpty(arr))
        {
            return result;
        }
        for (int i = 0; i < arr.size(); i++)
        {
            // 单行解析独立 try：任何一行脏数据（如 maxSizeMb 非数字）只跳过该行，不影响整体刷新
            try
            {
                JSONObject obj = arr.getJSONObject(i);
                if (Objects.isNull(obj))
                {
                    continue;
                }
                String name = StrUtil.trim(obj.getString("name"));
                // 大小上限（MB）：支持小数，向下取整到字节；非法或非正数跳过
                Double mb = obj.getDouble("maxSizeMb");
                if (StrUtil.isBlank(name) || Objects.isNull(mb) || mb <= 0)
                {
                    continue;
                }
                // 扩展名：数组或逗号分隔字符串两种形态都接受
                Set<String> extSet = parseExtensions(obj.get("extensions"));
                if (extSet.isEmpty())
                {
                    continue;
                }
                UploadTypeLimit limit = new UploadTypeLimit();
                limit.setName(name);
                limit.setMaxSizeMb(mb.longValue());
                limit.setMaxBytes((long) (mb * 1024 * 1024));
                limit.setExtensions(extSet);
                result.add(limit);
            }
            catch (Exception e)
            {
                // 脏行容错：记录后跳过，避免单行污染整份配置
                log.warn("解析 oss.uploadTypeLimits 第{}行失败，已跳过: {}", i, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 解析扩展名集合：兼容 JSON 数组与逗号分隔字符串；统一去前导点、转小写、去重。
     *
     * @param raw extensions 原始值（List 或 String）
     * @return 扩展名集合（小写）
     */
    private Set<String> parseExtensions(Object raw)
    {
        Set<String> extSet = new HashSet<>();
        if (Objects.isNull(raw))
        {
            return extSet;
        }
        List<String> parts = new ArrayList<>();
        if (raw instanceof Collection<?> coll)
        {
            for (Object o : coll)
            {
                if (Objects.nonNull(o))
                {
                    parts.add(String.valueOf(o));
                }
            }
        }
        else
        {
            // 字符串形态：逗号 / 中文逗号 / 空白分隔
            for (String s : String.valueOf(raw).split("[,，\\s]+"))
            {
                parts.add(s);
            }
        }
        for (String p : parts)
        {
            String e = StrUtil.trim(p);
            if (StrUtil.isNotBlank(e))
            {
                extSet.add(StrUtil.removePrefix(e, ".").toLowerCase());
            }
        }
        return extSet;
    }

    /**
     * 脱敏处理
     *
     * @param map 配置Map
     * @param key 需要脱敏的键
     */
    private void desensitize(Map<String, String> map, String key)
    {
        String value = map.get(key);
        if (StrUtil.isNotBlank(value) && value.length() > 8)
        {
            map.put(key, value.substring(0, 4) + "****" + value.substring(value.length() - 4));
        }
    }
}
