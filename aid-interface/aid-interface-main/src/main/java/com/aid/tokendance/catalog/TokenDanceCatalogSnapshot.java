package com.aid.tokendance.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 保存一份已经完成结构校验的 TokenDance 模型目录。 */
final class TokenDanceCatalogSnapshot {
    private final String catalogVersion;
    private final String publishedAt;
    private final String minimumAppVersion;
    private final String source;
    private final String sourceUrl;
    private final Instant loadedAt;
    private final JsonNode bundle;
    private final Map<String, JsonNode> entries;
    private final TokenDanceCapabilityTemplateRegistry capabilities;
    private final TokenDanceCatalogPricingCompiler.PricingContext pricingContext;

    TokenDanceCatalogSnapshot(
            String catalogVersion,
            String publishedAt,
            String minimumAppVersion,
            String source,
            String sourceUrl,
            Instant loadedAt,
            JsonNode bundle,
            Map<String, JsonNode> entries,
            TokenDanceCapabilityTemplateRegistry capabilities,
            TokenDanceCatalogPricingCompiler.PricingContext pricingContext) {
        this.catalogVersion = catalogVersion;
        this.publishedAt = publishedAt;
        this.minimumAppVersion = minimumAppVersion;
        this.source = source;
        this.sourceUrl = sourceUrl;
        this.loadedAt = loadedAt;
        this.bundle = bundle;
        this.entries = entries;
        this.capabilities = capabilities;
        this.pricingContext = pricingContext;
    }

    String catalogVersion() { return catalogVersion; }
    String publishedAt() { return publishedAt; }
    String minimumAppVersion() { return minimumAppVersion; }
    String source() { return source; }
    String sourceUrl() { return sourceUrl; }
    Instant loadedAt() { return loadedAt; }
    JsonNode bundle() { return bundle; }
    Map<String, JsonNode> entries() { return entries; }
    TokenDanceCapabilityTemplateRegistry capabilities() { return capabilities; }
    TokenDanceCatalogPricingCompiler.PricingContext pricingContext() { return pricingContext; }

    String minimumAppVersion(String modelId, String protocol) {
        String resolved = minimumAppVersion;
        for (JsonNode layer : compatibilityLayers(modelId, protocol)) {
            if (layer.path("minimumAppVersion").isTextual()
                    && !layer.path("minimumAppVersion").asText().isBlank()) {
                resolved = layer.path("minimumAppVersion").asText();
            }
        }
        return resolved;
    }

    int capabilityContractVersion(String modelId, String protocol) {
        return contractVersion("capabilityContractVersion", modelId, protocol);
    }

    int billingContractVersion(String modelId, String protocol) {
        return contractVersion("billingContractVersion", modelId, protocol);
    }

    boolean deprecated(String modelId, String protocol) {
        boolean deprecated = false;
        for (JsonNode layer : compatibilityLayers(modelId, protocol)) {
            if (layer.path("deprecated").isBoolean()) deprecated = layer.path("deprecated").asBoolean();
        }
        return deprecated;
    }

    List<String> requiredFeatures(String modelId, String protocol) {
        java.util.LinkedHashSet<String> features = new java.util.LinkedHashSet<>();
        for (JsonNode layer : compatibilityLayers(modelId, protocol)) {
            JsonNode configured = layer.path("requiredFeatures");
            if (configured.isArray()) configured.forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) features.add(value.asText());
            });
        }
        return List.copyOf(features);
    }

    private int contractVersion(String field, String modelId, String protocol) {
        int resolved = bundle.path(field).asInt(1);
        for (JsonNode layer : compatibilityLayers(modelId, protocol)) {
            if (layer.path(field).isIntegralNumber()) resolved = layer.path(field).asInt();
        }
        return resolved;
    }

    private List<JsonNode> compatibilityLayers(String modelId, String protocol) {
        JsonNode compatibility = bundle.path("compatibility");
        JsonNode model = compatibility.path("models").path(modelId);
        return List.of(
                compatibility,
                compatibility.path("protocols").path(protocol),
                model,
                model.path("protocols").path(protocol));
    }
}
