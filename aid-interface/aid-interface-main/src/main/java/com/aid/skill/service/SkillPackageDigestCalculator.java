package com.aid.skill.service;

import cn.hutool.crypto.SecureUtil;
import com.aid.skill.domain.AidSkillRelation;
import com.aid.skill.domain.AidSkillResource;
import com.aid.skill.domain.AidSkillVersion;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据库 Skill 包的稳定摘要算法；发布端和运行端必须共同使用。 */
public final class SkillPackageDigestCalculator {
    public static final String ALGORITHM_V1 = "aid-db-package-v1";
    public static final String ALGORITHM_V2 = "aid-db-package-v2";
    public static final String ALGORITHM_V3 = "aid-db-package-v3";

    private SkillPackageDigestCalculator() { }

    public static String calculate(String skillCode, AidSkillVersion version,
                                   List<AidSkillResource> resources,
                                   List<AidSkillRelation> relations) {
        String algorithm = digestAlgorithm(version.getManifestJson());
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("format", algorithm);
        basis.put("skillCode", skillCode);
        basis.put("versionCode", version.getVersionCode());
        basis.put("visibility", version.getVisibility());
        basis.put("invocationScope", version.getInvocationScope());
        basis.put("executorType", version.getExecutorType());
        basis.put("modelCode", version.getModelCode());
        if (ALGORITHM_V3.equals(algorithm)) {
            basis.put("modelConfigJson", value(version.getModelConfigJson()));
        }
        basis.put("systemPromptDigest", SecureUtil.sha256(value(version.getSystemPrompt())));
        basis.put("inputSchemaDigest", SecureUtil.sha256(value(version.getInputSchemaJson())));
        basis.put("outputSchemaDigest", SecureUtil.sha256(value(version.getOutputSchemaJson())));
        basis.put("definitionDigest", SecureUtil.sha256(value(version.getDefinitionJson())));
        basis.put("maxOutputTokens", version.getMaxOutputTokens());
        basis.put("contextWindowTokens", version.getContextWindowTokens());
        basis.put("safetyMarginTokens", version.getSafetyMarginTokens());
        if (ALGORITHM_V2.equals(algorithm) || ALGORITHM_V3.equals(algorithm)) {
            basis.put("manifestDigest", SecureUtil.sha256(value(version.getManifestJson())));
        }
        basis.put("resources", resources.stream()
                .map(resource -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", resource.getResourceKey());
                    item.put("type", resource.getResourceType());
                    item.put("mimeType", resource.getMimeType());
                    item.put("digest", resource.getContentDigest());
                    item.put("sizeBytes", resource.getSizeBytes());
                    item.put("routeJson", value(resource.getRouteJson()));
                    return item;
                }).toList());
        basis.put("relations", relations.stream()
                .sorted(Comparator.comparing(AidSkillRelation::getRelationKey))
                .map(relation -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", relation.getRelationType());
                    item.put("key", relation.getRelationKey());
                    item.put("childSkillId", relation.getChildSkillId());
                    item.put("childVersionId", relation.getChildVersionId());
                    item.put("required", relation.getRequiredFlag());
                    return item;
                }).toList());
        return SecureUtil.sha256(JSON.toJSONString(basis));
    }

    private static String digestAlgorithm(String manifestJson) {
        if (manifestJson == null) {
            return ALGORITHM_V1;
        }
        JSONObject manifest = JSON.parseObject(manifestJson);
        String algorithm = manifest == null ? null : manifest.getString("digestAlgorithm");
        if (algorithm == null || ALGORITHM_V1.equals(algorithm)) {
            return ALGORITHM_V1;
        }
        if (ALGORITHM_V2.equals(algorithm)) {
            return ALGORITHM_V2;
        }
        if (ALGORITHM_V3.equals(algorithm)) {
            return ALGORITHM_V3;
        }
        throw new IllegalArgumentException("不支持的Skill数据库包摘要算法");
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
