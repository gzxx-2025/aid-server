package com.aid.skill.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.skill.domain.AidSkillRelation;
import com.aid.skill.domain.AidSkillResource;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillRelationMapper;
import com.aid.skill.mapper.AidSkillResourceMapper;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/** 按版本包清单确定性路由并加载少量命中参考资源。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillPackageResourceLoader {
    private static final int MAX_SELECTED_RESOURCES = 4;
    private static final int MAX_DATABASE_PACKAGES = 64;
    private static final int DATABASE_LOCK_STRIPES = 64;
    private static final String NORMAL = "0";
    private static final String DATABASE_SOURCE = "DATABASE";
    private final AidSkillResourceMapper resourceMapper;
    private final AidSkillRelationMapper relationMapper;
    private final Map<String, PackageManifest> manifestCache = new ConcurrentHashMap<>();
    private final Map<String, SelectedResource> resourceCache = new ConcurrentHashMap<>();
    private final ReentrantLock[] databaseLoadLocks = createDatabaseLoadLocks();
    private final Map<Long, DatabasePackage> databaseCache = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, DatabasePackage> eldest) {
            return size() > MAX_DATABASE_PACKAGES;
        }
    };

    public List<SelectedResource> select(String skillCode, AidSkillVersion version,
                                         String operation, String intent) {
        if (isDatabasePackage(version)) {
            DatabasePackage databasePackage = databasePackage(skillCode, version);
            return select(databasePackage.routes(), operation, intent);
        }
        String coordinate = coordinate(skillCode, version.getVersionCode());
        PackageManifest manifest = manifestCache.computeIfAbsent(coordinate, this::loadManifest);
        return select(manifest.getResources().stream().map(route -> new RoutedResource(route,
                resourceCache.computeIfAbsent(coordinate + ":" + route.getKey(),
                        ignored -> loadResource(skillCode, version.getVersionCode(), route)))).toList(),
                operation, intent);
    }

    private List<SelectedResource> select(List<RoutedResource> routes, String operation, String intent) {
        String routeText = (StrUtil.blankToDefault(operation, "") + " "
                + StrUtil.blankToDefault(intent, "")).toLowerCase(Locale.ROOT);
        List<SelectedResource> selected = new ArrayList<>();
        for (RoutedResource routed : routes) {
            if (!matches(routed.route(), operation, routeText)) {
                continue;
            }
            selected.add(routed.resource());
            if (selected.size() >= MAX_SELECTED_RESOURCES) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    public void verifyVersion(String skillCode, AidSkillVersion version) {
        if (isDatabasePackage(version)) {
            verifyDatabasePackage(skillCode, version);
            return;
        }
        PackageManifest manifest = manifestCache.computeIfAbsent(
                coordinate(skillCode, version.getVersionCode()), this::loadManifest);
        if (!Objects.equals(manifest.getVersion(), version.getVersionCode())
                || !Objects.equals(manifest.getCalculatedPackageDigest(), version.getPackageDigest())
                || !Objects.equals(manifest.getSystemPrompt(), version.getSystemPrompt())) {
            throw new ServiceException("Skill版本包摘要不匹配");
        }
    }

    /**
     * Runtime 热路径校验。版本发布后不可变，命中时只比较版本行自带的摘要/配置指纹，
     * 不重复读取最多 512 KiB 的数据库资源正文；首次命中仍会完整验包。
     */
    public void verifyVersionCached(String skillCode, AidSkillVersion version) {
        if (isDatabasePackage(version)) {
            databasePackage(skillCode, version);
            return;
        }
        verifyVersion(skillCode, version);
    }

    public void verifyVersionsCached(Map<String, AidSkillVersion> versionsBySkillCode) {
        if (versionsBySkillCode == null || versionsBySkillCode.isEmpty()) {
            return;
        }
        versionsBySkillCode.forEach(this::verifyVersionCached);
    }

    /** 激活前批量校验固定版本，数据库包仅执行两次批量资源查询。 */
    public void verifyVersions(Map<String, AidSkillVersion> versionsBySkillCode) {
        if (versionsBySkillCode == null || versionsBySkillCode.isEmpty()) {
            return;
        }
        Map<String, AidSkillVersion> databaseVersions = new LinkedHashMap<>();
        for (Map.Entry<String, AidSkillVersion> entry : versionsBySkillCode.entrySet()) {
            if (!isDatabasePackage(entry.getValue())) {
                verifyVersion(entry.getKey(), entry.getValue());
                continue;
            }
            databaseVersions.put(entry.getKey(), entry.getValue());
        }
        if (databaseVersions.isEmpty()) {
            return;
        }
        Map<Long, DatabasePackage> observedCache = new LinkedHashMap<>();
        synchronized (databaseCache) {
            databaseVersions.values().stream().map(AidSkillVersion::getId).distinct()
                    .forEach(versionId -> observedCache.put(versionId, databaseCache.get(versionId)));
        }
        List<ReentrantLock> loadLocks = databaseVersions.values().stream()
                .map(AidSkillVersion::getId).map(this::databaseLoadLockIndex).distinct().sorted()
                .map(index -> databaseLoadLocks[index]).toList();
        loadLocks.forEach(ReentrantLock::lock);
        try {
            Map<String, AidSkillVersion> pending = new LinkedHashMap<>();
            for (Map.Entry<String, AidSkillVersion> entry : databaseVersions.entrySet()) {
                synchronized (databaseCache) {
                    DatabasePackage cached = databaseCache.get(entry.getValue().getId());
                    if (cached != null && cached != observedCache.get(entry.getValue().getId())) {
                        requireMatchingCache(entry.getKey(), entry.getValue(), cached);
                        continue;
                    }
                }
                pending.put(entry.getKey(), entry.getValue());
            }
            if (pending.isEmpty()) {
                return;
            }
            Set<Long> versionIds = pending.values().stream().map(AidSkillVersion::getId)
                    .collect(Collectors.toSet());
            Map<Long, List<AidSkillResource>> resourcesByVersion = resourceMapper.selectList(
                            Wrappers.<AidSkillResource>lambdaQuery()
                                    .select(AidSkillResource::getId, AidSkillResource::getSkillVersionId,
                                            AidSkillResource::getResourceKey, AidSkillResource::getResourceType,
                                            AidSkillResource::getObjectKey, AidSkillResource::getContentDigest,
                                            AidSkillResource::getMimeType, AidSkillResource::getSizeBytes,
                                            AidSkillResource::getRouteJson, AidSkillResource::getContentText,
                                            AidSkillResource::getStatus, AidSkillResource::getDelFlag)
                                    .in(AidSkillResource::getSkillVersionId, versionIds)
                                    .eq(AidSkillResource::getStatus, NORMAL)
                                    .eq(AidSkillResource::getDelFlag, NORMAL)
                                    .orderByAsc(AidSkillResource::getSkillVersionId)
                                    .orderByAsc(AidSkillResource::getId)).stream()
                    .collect(Collectors.groupingBy(AidSkillResource::getSkillVersionId));
            Map<Long, List<AidSkillRelation>> relationsByVersion = relationMapper.selectList(
                            Wrappers.<AidSkillRelation>lambdaQuery()
                                    .in(AidSkillRelation::getParentVersionId, versionIds)
                                    .eq(AidSkillRelation::getDelFlag, NORMAL)
                                    .orderByAsc(AidSkillRelation::getParentVersionId)
                                    .orderByAsc(AidSkillRelation::getId)).stream()
                    .collect(Collectors.groupingBy(AidSkillRelation::getParentVersionId));
            for (Map.Entry<String, AidSkillVersion> entry : pending.entrySet()) {
                AidSkillVersion version = entry.getValue();
                DatabasePackage loaded = buildDatabasePackage(entry.getKey(), version,
                        resourcesByVersion.getOrDefault(version.getId(), List.of()),
                        relationsByVersion.getOrDefault(version.getId(), List.of()));
                synchronized (databaseCache) {
                    databaseCache.put(version.getId(), loaded);
                }
            }
        } finally {
            for (int index = loadLocks.size() - 1; index >= 0; index--) {
                loadLocks.get(index).unlock();
            }
        }
    }

    private DatabasePackage databasePackage(String skillCode, AidSkillVersion version) {
        ReentrantLock loadLock = databaseLoadLocks[databaseLoadLockIndex(version.getId())];
        loadLock.lock();
        try {
            synchronized (databaseCache) {
                DatabasePackage cached = databaseCache.get(version.getId());
                if (cached != null) {
                    return requireMatchingCache(skillCode, version, cached);
                }
            }
            DatabasePackage loaded = loadDatabasePackage(skillCode, version);
            synchronized (databaseCache) {
                databaseCache.put(version.getId(), loaded);
            }
            return loaded;
        } finally {
            loadLock.unlock();
        }
    }

    private DatabasePackage verifyDatabasePackage(String skillCode, AidSkillVersion version) {
        DatabasePackage observed;
        synchronized (databaseCache) {
            observed = databaseCache.get(version.getId());
        }
        ReentrantLock loadLock = databaseLoadLocks[databaseLoadLockIndex(version.getId())];
        loadLock.lock();
        try {
            synchronized (databaseCache) {
                DatabasePackage refreshed = databaseCache.get(version.getId());
                if (refreshed != null && refreshed != observed) {
                    return requireMatchingCache(skillCode, version, refreshed);
                }
            }
            DatabasePackage loaded = loadDatabasePackage(skillCode, version);
            synchronized (databaseCache) {
                databaseCache.put(version.getId(), loaded);
            }
            return loaded;
        } finally {
            loadLock.unlock();
        }
    }

    private static ReentrantLock[] createDatabaseLoadLocks() {
        ReentrantLock[] locks = new ReentrantLock[DATABASE_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private int databaseLoadLockIndex(Long versionId) {
        return Math.floorMod(Long.hashCode(versionId), DATABASE_LOCK_STRIPES);
    }

    private DatabasePackage requireMatchingCache(String skillCode, AidSkillVersion version,
                                                  DatabasePackage cached) {
        if (!Objects.equals(cached.packageDigest(), version.getPackageDigest())
                || !Objects.equals(cached.versionFingerprint(), versionFingerprint(skillCode, version))) {
            log.error("Skill数据库包缓存摘要冲突, skillCode={}, versionId={}", skillCode, version.getId());
            throw new ServiceException("Skill版本包摘要不匹配");
        }
        return cached;
    }

    public String readPublishedResource(AidSkillVersion version, AidSkillResource resource) {
        if (isDatabasePackage(version)) {
            return verifiedDatabaseContent(resource);
        }
        String objectKey = resource.getObjectKey();
        if (StrUtil.isBlank(objectKey) || !objectKey.startsWith("classpath:")) {
            log.error("Skill classpath资源地址错误, versionId={}, resourceKey={}, objectKeyLength={}",
                    version.getId(), resource.getResourceKey(), StrUtil.length(objectKey));
            throw new ServiceException("Skill资源地址无效");
        }
        try {
            String content = read(objectKey.substring("classpath:".length()));
            verifyResourceDigest(resource, content);
            return content;
        } catch (IOException error) {
            log.error("Skill classpath资源读取失败, versionId={}, resourceKey={}, objectKey={}",
                    version.getId(), resource.getResourceKey(), objectKey, error);
            throw new ServiceException("Skill包资源不可用");
        }
    }

    private DatabasePackage loadDatabasePackage(String skillCode, AidSkillVersion version) {
        List<AidSkillResource> resources = resourceMapper.selectList(Wrappers.<AidSkillResource>lambdaQuery()
                .select(AidSkillResource::getId, AidSkillResource::getSkillVersionId,
                        AidSkillResource::getResourceKey, AidSkillResource::getResourceType,
                        AidSkillResource::getObjectKey, AidSkillResource::getContentDigest,
                        AidSkillResource::getMimeType, AidSkillResource::getSizeBytes,
                        AidSkillResource::getRouteJson, AidSkillResource::getContentText,
                        AidSkillResource::getStatus, AidSkillResource::getDelFlag)
                .eq(AidSkillResource::getSkillVersionId, version.getId())
                .eq(AidSkillResource::getStatus, NORMAL).eq(AidSkillResource::getDelFlag, NORMAL)
                .orderByAsc(AidSkillResource::getId));
        List<AidSkillRelation> relations = relationMapper.selectList(Wrappers.<AidSkillRelation>lambdaQuery()
                .eq(AidSkillRelation::getParentVersionId, version.getId())
                .eq(AidSkillRelation::getDelFlag, NORMAL).orderByAsc(AidSkillRelation::getId));
        return buildDatabasePackage(skillCode, version, resources, relations);
    }

    private DatabasePackage buildDatabasePackage(String skillCode, AidSkillVersion version,
                                                  List<AidSkillResource> resources,
                                                  List<AidSkillRelation> relations) {
        String calculated;
        try {
            calculated = SkillPackageDigestCalculator.calculate(skillCode, version, resources, relations);
        } catch (RuntimeException error) {
            log.error("Skill数据库包摘要算法无效, skillCode={}, versionId={}",
                    skillCode, version.getId(), error);
            throw new ServiceException("Skill版本包摘要算法无效");
        }
        if (!Objects.equals(calculated, version.getPackageDigest())) {
            log.error("Skill数据库包摘要错误, skillCode={}, versionId={}, expected={}, actual={}",
                    skillCode, version.getId(), version.getPackageDigest(), calculated);
            throw new ServiceException("Skill版本包摘要不匹配");
        }
        List<RoutedResource> routes = new ArrayList<>();
        for (AidSkillResource resource : resources) {
            ResourceRoute route;
            try {
                route = StrUtil.isBlank(resource.getRouteJson())
                        ? new ResourceRoute() : JSON.parseObject(resource.getRouteJson(), ResourceRoute.class);
            } catch (RuntimeException error) {
                log.error("Skill数据库资源路由错误, versionId={}, resourceKey={}",
                        version.getId(), resource.getResourceKey(), error);
                throw new ServiceException("Skill资源路由无效");
            }
            if (route == null) {
                route = new ResourceRoute();
            }
            route.setKey(resource.getResourceKey());
            route.setDigest(resource.getContentDigest());
            String content = verifiedDatabaseContent(resource);
            routes.add(new RoutedResource(route,
                    new SelectedResource(resource.getResourceKey(), resource.getContentDigest(), content)));
        }
        return new DatabasePackage(version.getPackageDigest(), versionFingerprint(skillCode, version),
                List.copyOf(routes));
    }

    private String versionFingerprint(String skillCode, AidSkillVersion version) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("skillCode", skillCode);
        basis.put("versionCode", version.getVersionCode());
        basis.put("visibility", version.getVisibility());
        basis.put("invocationScope", version.getInvocationScope());
        basis.put("executorType", version.getExecutorType());
        basis.put("modelCode", version.getModelCode());
        basis.put("modelConfigJson", value(version.getModelConfigJson()));
        basis.put("packageDigest", version.getPackageDigest());
        basis.put("manifestDigest", SecureUtil.sha256(value(version.getManifestJson())));
        basis.put("skillId", version.getSkillId());
        basis.put("publishStatus", version.getPublishStatus());
        basis.put("status", version.getStatus());
        basis.put("delFlag", version.getDelFlag());
        basis.put("systemPromptDigest", SecureUtil.sha256(value(version.getSystemPrompt())));
        basis.put("inputSchemaDigest", SecureUtil.sha256(value(version.getInputSchemaJson())));
        basis.put("outputSchemaDigest", SecureUtil.sha256(value(version.getOutputSchemaJson())));
        basis.put("definitionDigest", SecureUtil.sha256(value(version.getDefinitionJson())));
        basis.put("maxOutputTokens", version.getMaxOutputTokens());
        basis.put("contextWindowTokens", version.getContextWindowTokens());
        basis.put("safetyMarginTokens", version.getSafetyMarginTokens());
        return SecureUtil.sha256(JSON.toJSONString(basis));
    }

    private String value(String source) {
        return source == null ? "" : source;
    }

    private String verifiedDatabaseContent(AidSkillResource resource) {
        if (StrUtil.isBlank(resource.getObjectKey())) {
            log.error("Skill数据库资源地址错误, versionId={}, resourceKey={}",
                    resource.getSkillVersionId(), resource.getResourceKey());
            throw new ServiceException("Skill资源地址无效");
        }
        String content = resource.getContentText();
        if (content == null && inheritedScreenplayClasspathResource(resource.getObjectKey())) {
            try {
                content = read(resource.getObjectKey().substring("classpath:".length()));
            } catch (IOException error) {
                log.error("Skill继承资源读取失败, versionId={}, resourceKey={}",
                        resource.getSkillVersionId(), resource.getResourceKey(), error);
                throw new ServiceException("Skill资源内容缺失");
            }
        }
        if (content != null && !resource.getObjectKey().startsWith("database:")
                && !resource.getObjectKey().startsWith("classpath:")) {
            throw new ServiceException("Skill资源地址无效");
        }
        if (content == null) {
            log.error("Skill数据库资源内容缺失, versionId={}, resourceKey={}",
                    resource.getSkillVersionId(), resource.getResourceKey());
            throw new ServiceException("Skill资源内容缺失");
        }
        verifyResourceDigest(resource, content);
        return content;
    }

    private boolean inheritedScreenplayClasspathResource(String objectKey) {
        return objectKey != null && objectKey.matches(
                "classpath:skills/(?:screenplay|screenplay-write|screenplay-review)/"
                        + "[0-9]+\\.[0-9]+\\.[0-9]+/references/[a-z0-9-]+\\.md");
    }

    private void verifyResourceDigest(AidSkillResource resource, String content) {
        String digest = SecureUtil.sha256(content);
        if (!Objects.equals(digest, resource.getContentDigest())
                || !Objects.equals((long) content.getBytes(StandardCharsets.UTF_8).length,
                resource.getSizeBytes())) {
            log.error("Skill资源完整性错误, versionId={}, resourceKey={}",
                    resource.getSkillVersionId(), resource.getResourceKey());
            throw new ServiceException("Skill资源摘要不匹配");
        }
    }

    public boolean isDatabasePackage(AidSkillVersion version) {
        if (version == null || version.getId() == null || StrUtil.isBlank(version.getManifestJson())) {
            return false;
        }
        try {
            return DATABASE_SOURCE.equalsIgnoreCase(
                    JSON.parseObject(version.getManifestJson()).getString("source"));
        } catch (RuntimeException error) {
            log.error("Skill包清单解析失败, versionId={}, manifestLength={}",
                    version.getId(), version.getManifestJson().length(), error);
            throw new ServiceException("Skill包清单无效");
        }
    }

    private boolean matches(ResourceRoute route, String operation, String routeText) {
        if (Boolean.TRUE.equals(route.getAlways())) {
            return true;
        }
        if (route.getOperations() != null && route.getOperations().stream()
                .anyMatch(value -> value.equalsIgnoreCase(StrUtil.blankToDefault(operation, "")))) {
            return true;
        }
        return route.getKeywords() != null && route.getKeywords().stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(routeText::contains);
    }

    private PackageManifest loadManifest(String coordinate) {
        int separator = coordinate.lastIndexOf('@');
        String skillCode = coordinate.substring(0, separator);
        String versionCode = coordinate.substring(separator + 1);
        String basePath = "skills/" + skillCode + "/" + versionCode + "/";
        String path = basePath + "aid-skill.json";
        try {
            String content = read(path);
            PackageManifest manifest = JSON.parseObject(content, PackageManifest.class);
            if (manifest == null || !skillCode.equals(manifest.getCode())
                    || !versionCode.equals(manifest.getVersion()) || manifest.getResources() == null) {
                throw new ServiceException("Skill包清单无效");
            }
            for (ResourceRoute route : manifest.getResources()) {
                if (StrUtil.isBlank(route.getKey()) || StrUtil.isBlank(route.getPath())
                        || StrUtil.isBlank(route.getDigest())) {
                    throw new ServiceException("Skill资源清单无效");
                }
                String resourceContent = read(basePath + route.getPath());
                String resourceDigest = SecureUtil.sha256(resourceContent);
                if (!route.getDigest().equalsIgnoreCase(resourceDigest)) {
                    throw new ServiceException("Skill资源摘要不匹配");
                }
                resourceCache.putIfAbsent(coordinate + ":" + route.getKey(),
                        new SelectedResource(route.getKey(), resourceDigest, resourceContent));
            }
            String skillDigest = SecureUtil.sha256(read(basePath + "SKILL.md"));
            String manifestDigest = SecureUtil.sha256(content);
            String resourceLock = manifest.getResources().stream()
                    .sorted(Comparator.comparing(ResourceRoute::getKey))
                    .map(value -> value.getKey() + ":" + value.getDigest()).collect(Collectors.joining(","));
            String basis = "aid-package-v1|" + skillCode + "|" + manifest.getVersion()
                    + "|skill=" + skillDigest + "|manifest=" + manifestDigest + "|resources=" + resourceLock;
            manifest.setCalculatedPackageDigest(SecureUtil.sha256(basis));
            return manifest;
        } catch (IOException | RuntimeException error) {
            log.error("Skill classpath包加载失败, coordinate={}", coordinate, error);
            if (error instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException("Skill包资源不可用");
        }
    }

    private SelectedResource loadResource(String skillCode, String versionCode, ResourceRoute route) {
        String path = "skills/" + skillCode + "/" + versionCode + "/" + route.getPath();
        try {
            String content = read(path);
            String digest = SecureUtil.sha256(content);
            if (StrUtil.isBlank(route.getDigest())) {
                throw new ServiceException("Skill资源缺少摘要");
            }
            if (!route.getDigest().equalsIgnoreCase(digest)) {
                throw new ServiceException("Skill资源摘要不匹配");
            }
            return new SelectedResource(route.getKey(), digest, content);
        } catch (IOException error) {
            log.error("Skill classpath资源加载失败, skillCode={}, versionCode={}, resourceKey={}, path={}",
                    skillCode, versionCode, route.getKey(), path, error);
            throw new ServiceException("Skill包资源不可用");
        }
    }

    private String read(String path) throws IOException {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String coordinate(String skillCode, String versionCode) {
        if (StrUtil.isBlank(skillCode) || StrUtil.isBlank(versionCode) || skillCode.contains("@")) {
            throw new ServiceException("Skill包坐标无效");
        }
        return skillCode + "@" + versionCode;
    }

    public record SelectedResource(String resourceKey, String digest, String content) { }

    @Data
    private static class PackageManifest {
        private String code;
        private String version;
        private String systemPrompt;
        private String calculatedPackageDigest;
        private List<ResourceRoute> resources;
    }

    @Data
    private static class ResourceRoute {
        private String key;
        private String path;
        private String digest;
        private Boolean always;
        private List<String> operations;
        private List<String> keywords;
    }

    private record RoutedResource(ResourceRoute route, SelectedResource resource) { }

    private record DatabasePackage(String packageDigest, String versionFingerprint,
                                   List<RoutedResource> routes) { }
}
