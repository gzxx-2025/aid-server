package com.aid.tokendance.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** TokenDance 在线模型目录，远端不可用时自动使用可信缓存或内置快照。 */
public interface ITokenDanceCatalogService {
    List<JsonNode> list(String keyword, Long providerId);
    JsonNode detail(String modelId);
    JsonNode preview(Long providerId, String modelId, String protocol);
    List<ImportResult> importModels(Long providerId, String catalogVersion, List<Selection> selections);
    void repairEmptyPricing(Long providerId, String modelId, String protocol, Long configVersion);
    JsonNode status();
    JsonNode refresh();

    record Selection(String modelId, String protocol) { }
    record ImportResult(String modelId, String protocol, Long localModelId, String status) { }
}
