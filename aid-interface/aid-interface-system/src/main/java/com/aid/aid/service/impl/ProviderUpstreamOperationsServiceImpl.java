package com.aid.aid.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.dto.ProviderUpstreamTaskQuery;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IProviderUpstreamOperationsService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ProviderEndpointUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 可发现式供应商后台能力；可灵提供余额/任务，MiniMax H3 提供近 7 天视频任务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderUpstreamOperationsServiceImpl implements IProviderUpstreamOperationsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KLING_PROVIDER_CODE = "kling";
    private static final String KLING_AUTH_PREFIX = "Bearer ";
    private static final int HTTP_TIMEOUT_MS = 120_000;
    private static final long BALANCE_CACHE_MS = 1_100L;
    private static final Set<String> TASK_STATUSES = Set.of("submitted", "processing", "succeeded", "failed");
    private static final Set<String> PRODUCT_TYPES = Set.of("video", "image", "try_on");
    private static final Set<String> SEARCH_TYPES = Set.of("task_ids", "external_task_ids");
    private static final String MINIMAX_PROVIDER_CODE = "minimax";
    private static final Set<String> MINIMAX_TASK_STATUSES = Set.of(
        "queued", "running", "succeeded", "failed", "cancelled");
    private static final Set<String> MINIMAX_TASK_TYPES = Set.of("generation");

    private final IAidAiProviderService providerService;
    private final Map<Long, BalanceCache> balanceCache = new ConcurrentHashMap<>();
    private final Map<Long, Object> balanceLocks = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> capabilities(Long providerId) {
        AidAiProvider provider = requireProvider(providerId);
        boolean kling = KLING_PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(provider.getProviderCode()));
        boolean minimax = MINIMAX_PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(provider.getProviderCode()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("balance", kling);
        result.put("upstreamTasks", kling || minimax);
        if (kling) {
            result.put("taskStatuses", TASK_STATUSES);
            result.put("productTypes", PRODUCT_TYPES);
            result.put("taskSearchTypes", SEARCH_TYPES);
            result.put("balanceDelayNotice", "资源包余量统计可能延迟约 12 小时");
        }
        if (minimax) {
            result.put("taskStatuses", List.of("queued", "running", "succeeded", "failed", "cancelled"));
            result.put("productTypes", List.of("video"));
            result.put("taskTypes", List.of("generation"));
            result.put("taskSearchTypes", List.of("task_ids"));
            result.put("supportsTimeRange", false);
            result.put("recentDays", 7);
        }
        return result;
    }

    @Override
    public Map<String, Object> balance(Long providerId, Long startTime, Long endTime, String resourcePackName) {
        AidAiProvider provider = requireKling(providerId);
        long now = Instant.now().toEpochMilli();
        long end = endTime == null ? now : endTime;
        long start = startTime == null ? end - 30L * 24L * 60L * 60L * 1000L : startTime;
        if (start <= 0 || end <= start) {
            throw failure("invalid balance time range", "余额时间无效");
        }
        String cacheKey = start + ":" + end + ":" + StrUtil.trimToEmpty(resourcePackName);
        BalanceCache cached = balanceCache.get(providerId);
        if (cached != null && cached.key().equals(cacheKey) && now - cached.createdAt() < BALANCE_CACHE_MS) {
            return cached.value();
        }
        synchronized (balanceLock(providerId)) {
            cached = balanceCache.get(providerId);
            now = Instant.now().toEpochMilli();
            if (cached != null && cached.key().equals(cacheKey) && now - cached.createdAt() < BALANCE_CACHE_MS) {
                return cached.value();
            }
            if (cached != null && now - cached.createdAt() < 1_000L) {
                // 不同筛选条件也不得突破官方 QPS<=1；让管理端稍后重试，不阻塞线程睡眠。
                throw failure("balance QPS exceeded", "查询过于频繁");
            }
            StringBuilder path = new StringBuilder(buildKlingBalancePath(provider.getTaskQuerySuffix()))
                .append("?start_time=").append(start).append("&end_time=").append(end);
            if (StrUtil.isNotBlank(resourcePackName)) {
                path.append("&resource_pack_name=")
                    .append(URLEncoder.encode(resourcePackName.trim(), StandardCharsets.UTF_8));
            }
            JsonNode data = request(provider, "GET", buildProviderUrl(provider, path.toString()), null).path("data");
            Map<String, Object> immutable = parseBalanceData(data, now);
            balanceCache.put(providerId, new BalanceCache(cacheKey, now, immutable));
            return immutable;
        }
    }

    @Override
    public Map<String, Object> tasks(Long providerId, ProviderUpstreamTaskQuery query) {
        AidAiProvider provider = requireProvider(providerId);
        if (MINIMAX_PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(provider.getProviderCode()))) {
            return minimaxTasks(requireMinimax(provider), query);
        }
        provider = requireKling(providerId);
        ProviderUpstreamTaskQuery safe = query == null ? new ProviderUpstreamTaskQuery() : query;
        boolean hasSearchType = StrUtil.isNotBlank(safe.getSearchType());
        boolean hasSearchValue = StrUtil.isNotBlank(safe.getSearchValue());
        if (hasSearchType != hasSearchValue) {
            throw failure("incomplete exact search", "搜索参数不全");
        }
        if (hasSearchType) {
            String path = buildExactSearchPath(provider.getTaskQuerySuffix(), safe);
            JsonNode data = request(provider, "GET", buildProviderUrl(provider, path), null).path("data");
            List<Map<String, Object>> items = data.isArray()
                ? MAPPER.convertValue(data, new TypeReference<List<Map<String, Object>>>() {}) : List.of();
            return Map.of("result", items, "nextCursor", "", "hasMore", false);
        }
        Map<String, Object> body = buildTaskListBody(safe, Instant.now().toEpochMilli());
        String listPath = buildTaskCollectionPath(provider.getTaskQuerySuffix());
        JsonNode data = request(provider, "POST", buildProviderUrl(provider, listPath),
            JSONUtil.toJsonStr(body)).path("data");
        if (!data.isObject()) {
            throw failure("task page data is not object", "任务响应异常");
        }
        return parseTaskPage(data);
    }

    private Map<String, Object> minimaxTasks(AidAiProvider provider, ProviderUpstreamTaskQuery query) {
        ProviderUpstreamTaskQuery safe = query == null ? new ProviderUpstreamTaskQuery() : query;
        MinimaxCursor state = StrUtil.isNotBlank(safe.getCursor())
            ? decodeMinimaxCursor(safe.getCursor()) : createMinimaxCursor(safe);
        String listPath = buildMinimaxListPath(provider.getTaskQuerySuffix(), state);
        JsonNode root = requestMinimax(provider, buildProviderUrl(provider, listPath));
        JsonNode rawItems = root.path("items");
        if (!rawItems.isArray() || !root.path("total").canConvertToInt()) {
            throw failure("invalid MiniMax task page", "任务响应异常");
        }
        List<Map<String, Object>> items = normalizeMinimaxTasks(rawItems, state.statuses());
        int total = Math.max(0, root.path("total").asInt());
        boolean hasMore = (long) state.page() * state.limit() < total;
        String nextCursor = hasMore ? encodeMinimaxCursor(state.nextPage()) : "";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", items);
        result.put("nextCursor", nextCursor);
        result.put("hasMore", hasMore);
        result.put("total", total);
        return result;
    }

    static String buildMinimaxListPath(String taskQuerySuffix, MinimaxCursor state) {
        StringBuilder path = new StringBuilder(buildTaskCollectionPath(taskQuerySuffix))
            .append("?page_num=").append(state.page())
            .append("&page_size=").append(state.limit());
        if (state.statuses().size() == 1) {
            path.append("&filter.status=").append(encode(state.statuses().get(0)));
        }
        for (String taskId : state.taskIds()) {
            path.append("&filter.task_ids=").append(encode(taskId));
        }
        if (StrUtil.isNotBlank(state.model())) {
            path.append("&filter.model=").append(encode(state.model()));
        }
        if (StrUtil.isNotBlank(state.taskType())) {
            path.append("&filter.task_type=").append(encode(state.taskType()));
        }
        return path.toString();
    }

    static MinimaxCursor createMinimaxCursor(ProviderUpstreamTaskQuery query) {
        int limit = query.getLimit() == null ? 20 : query.getLimit();
        if (limit < 1 || limit > 100) {
            throw failure("invalid MiniMax page size=" + limit, "查询数量无效");
        }
        List<String> statuses = normalizeMinimaxStatuses(query.getStatus());
        List<String> taskIds = List.of();
        boolean hasType = StrUtil.isNotBlank(query.getSearchType());
        boolean hasValue = StrUtil.isNotBlank(query.getSearchValue());
        if (hasType != hasValue) {
            throw failure("incomplete MiniMax exact search", "搜索参数不全");
        }
        if (hasType) {
            if (!"task_ids".equals(query.getSearchType())) {
                throw failure("invalid MiniMax exact search type", "搜索类型无效");
            }
            taskIds = splitTaskIds(query.getSearchValue());
        }
        String taskType = StrUtil.blankToDefault(StrUtil.trim(query.getTaskType()), "generation");
        if (!MINIMAX_TASK_TYPES.contains(taskType)) {
            throw failure("unsupported MiniMax task type=" + taskType, "任务类型无效");
        }
        String model = StrUtil.blankToDefault(StrUtil.trim(query.getModel()), "MiniMax-H3");
        return new MinimaxCursor(1, limit, statuses, taskIds, model, taskType);
    }

    static List<String> normalizeMinimaxStatuses(String raw) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String item : raw.split(",")) {
            String value = item.trim().toLowerCase();
            value = switch (value) {
                case "submitted" -> "queued";
                case "processing" -> "running";
                default -> value;
            };
            if (!MINIMAX_TASK_STATUSES.contains(value)) {
                throw failure("invalid MiniMax task status=" + value, "任务筛选无效");
            }
            if (!values.contains(value)) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    static String encodeMinimaxCursor(MinimaxCursor state) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(state);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception ex) {
            throw failure("cannot encode MiniMax cursor", "分页参数无效");
        }
    }

    static MinimaxCursor decodeMinimaxCursor(String cursor) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor.trim());
            MinimaxCursor state = MAPPER.readValue(json, MinimaxCursor.class);
            if (state.page() < 1 || state.limit() < 1 || state.limit() > 100
                || state.statuses() == null || state.taskIds() == null
                || state.statuses().stream().anyMatch(value -> !MINIMAX_TASK_STATUSES.contains(value))
                || !MINIMAX_TASK_TYPES.contains(state.taskType())
                || StrUtil.isBlank(state.model()) || state.model().length() > 128
                || state.taskIds().size() > 50
                || state.taskIds().stream().anyMatch(value -> StrUtil.isBlank(value) || value.length() > 256)) {
                throw new IllegalArgumentException("invalid cursor state");
            }
            return state;
        } catch (Exception ex) {
            throw failure("invalid MiniMax cursor", "分页参数无效");
        }
    }

    static List<Map<String, Object>> normalizeMinimaxTasks(JsonNode items, List<String> localStatuses) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : items) {
            String status = item.path("status").asText("").trim().toLowerCase();
            if (localStatuses.size() > 1 && !localStatuses.contains(status)) {
                continue;
            }
            Map<String, Object> normalized = MAPPER.convertValue(item, new TypeReference<>() {});
            normalized.put("task_id", item.path("id").asText(""));
            normalized.put("create_time", timestampMillis(item.path("created_at").asLong(0L)));
            normalized.put("update_time", timestampMillis(item.path("updated_at").asLong(0L)));
            normalized.put("message", item.path("error").path("message").asText(""));
            normalized.put("product_type", "video");
            result.add(normalized);
        }
        return result;
    }

    private static long timestampMillis(long value) {
        return value > 0 && value < 10_000_000_000L ? value * 1000L : value;
    }

    private static List<String> splitTaskIds(String raw) {
        List<String> ids = new ArrayList<>();
        for (String item : StrUtil.nullToEmpty(raw).split(",", -1)) {
            String value = item.trim();
            if (StrUtil.isBlank(value) || value.length() > 256) {
                throw failure("blank MiniMax task id", "搜索关键词无效");
            }
            ids.add(value);
        }
        if (ids.isEmpty() || ids.size() > 50) {
            throw failure("invalid MiniMax task id count", "任务编号过多");
        }
        return List.copyOf(ids);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String buildExactSearchPath(String taskQuerySuffix, ProviderUpstreamTaskQuery query) {
        if (query == null || !SEARCH_TYPES.contains(query.getSearchType())) {
            throw failure("invalid exact search type", "搜索类型无效");
        }
        List<String> encodedIds = new ArrayList<>();
        for (String raw : StrUtil.nullToEmpty(query.getSearchValue()).split(",", -1)) {
            String id = raw.trim();
            if (StrUtil.isBlank(id)) {
                throw failure("blank exact search id", "搜索关键词无效");
            }
            encodedIds.add(URLEncoder.encode(id, StandardCharsets.UTF_8));
        }
        if (encodedIds.size() > 50) {
            throw failure("too many exact search ids", "任务编号过多");
        }
        return buildTaskCollectionPath(taskQuerySuffix) + "?" + query.getSearchType()
            + "=" + String.join(",", encodedIds);
    }

    static String buildTaskCollectionPath(String taskQuerySuffix) {
        final String normalized;
        try {
            normalized = ProviderEndpointUtils.normalizeTaskQueryTemplate(taskQuerySuffix);
        } catch (IllegalArgumentException ex) {
            throw failure("invalid configured task query path", "查询路径无效");
        }
        String path = normalized.split("\\?", 2)[0];
        if (path.endsWith("/%s")) {
            path = path.substring(0, path.length() - 3);
        }
        if (path.contains("%s")) {
            throw failure("task placeholder cannot derive collection", "查询路径无效");
        }
        return path;
    }

    static String buildKlingBalancePath(String taskQuerySuffix) {
        String taskPath = buildTaskCollectionPath(taskQuerySuffix);
        int lastSlash = taskPath.lastIndexOf('/');
        if (lastSlash < 0) {
            throw failure("cannot derive Kling balance path", "查询路径无效");
        }
        return taskPath.substring(0, lastSlash) + "/account/costs";
    }

    private static String buildProviderUrl(AidAiProvider provider, String relativePath) {
        try {
            String[] parts = relativePath.split("\\?", 2);
            String url = ProviderEndpointUtils.buildSubmitUrl(provider.getBaseUrl(), parts[0]);
            return parts.length == 1 ? url : url + "?" + parts[1];
        } catch (IllegalArgumentException ex) {
            throw failure("invalid configured provider endpoint", "供应商路径无效");
        }
    }

    static Map<String, Object> parseTaskPage(JsonNode data) {
        if (data == null || !data.isObject()) {
            throw failure("invalid task page", "任务响应异常");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", MAPPER.convertValue(data.path("result"), new TypeReference<List<Map<String, Object>>>() {}));
        result.put("nextCursor", data.path("next_cursor").asText(""));
        result.put("hasMore", data.path("has_more").asBoolean(false));
        return result;
    }

    static Map<String, Object> buildTaskListBody(ProviderUpstreamTaskQuery query, long now) {
        ProviderUpstreamTaskQuery safe = query == null ? new ProviderUpstreamTaskQuery() : query;
        Map<String, Object> body = new LinkedHashMap<>();
        int limit = safe.getLimit() == null ? 100 : safe.getLimit();
        if (limit < 1 || limit > 500) {
            throw failure("invalid task page size=" + limit, "查询数量无效");
        }
        List<Map<String, Object>> filters = new ArrayList<>();
        addFilter(filters, "status", safe.getStatus(), TASK_STATUSES);
        addFilter(filters, "product_type", safe.getProductType(), PRODUCT_TYPES);
        if (StrUtil.isNotBlank(safe.getCursor())) {
            body.put("cursor", safe.getCursor().trim());
            body.put("limit", limit);
            if (!filters.isEmpty()) {
                body.put("filters", filters);
            }
            return body;
        }
        long end = safe.getEndTime() == null ? now : safe.getEndTime();
        long start = safe.getStartTime() == null ? end - 30L * 24L * 60L * 60L * 1000L : safe.getStartTime();
        if (start <= 0 || end <= start) {
            throw failure("invalid task time range", "任务时间无效");
        }
        body.put("start_time", start);
        body.put("end_time", end);
        body.put("limit", limit);
        if (!filters.isEmpty()) {
            body.put("filters", filters);
        }
        return body;
    }

    static Map<String, Object> parseBalanceData(JsonNode data, long queriedAt) {
        if (data == null || !data.isObject() || !data.has("code")) {
            throw failure("balance data code missing", "余额响应异常");
        }
        int innerCode;
        try {
            innerCode = Integer.parseInt(data.get("code").asText());
        } catch (Exception ex) {
            throw failure("balance data code invalid", "余额响应异常");
        }
        if (innerCode != 0) {
            throw failure("balance data rejected, code=" + innerCode, "余额查询失败");
        }
        Map<String, Object> result = MAPPER.convertValue(data, new TypeReference<>() {});
        result.remove("code");
        result.remove("msg");
        result.remove("message");
        result.put("delayNotice", "资源包余量统计可能延迟约 12 小时");
        result.put("queriedAt", queriedAt);
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    Object balanceLock(Long providerId) {
        return balanceLocks.computeIfAbsent(providerId, ignored -> new Object());
    }

    private static void addFilter(List<Map<String, Object>> filters, String key, String raw, Set<String> allowed) {
        if (StrUtil.isBlank(raw)) {
            return;
        }
        List<String> values = new ArrayList<>();
        for (String item : raw.split(",")) {
            String value = item.trim();
            if (!allowed.contains(value)) {
                throw failure("invalid task filter=" + value, "任务筛选无效");
            }
            values.add(value);
        }
        filters.add(Map.of("key", key, "values", values));
    }

    private JsonNode request(AidAiProvider provider, String method, String url, String body) {
        if (StrUtil.isBlank(provider.getApiKey())) {
            throw failure("missing provider api key, providerId=" + provider.getId(), "API密钥未配置");
        }
        try (HttpResponse response = "POST".equals(method)
            ? HttpRequest.post(url).header("Authorization", KLING_AUTH_PREFIX + provider.getApiKey().trim())
                .header("Content-Type", "application/json").body(body).timeout(HTTP_TIMEOUT_MS).execute()
            : HttpRequest.get(url).header("Authorization", KLING_AUTH_PREFIX + provider.getApiKey().trim())
                .header("Content-Type", "application/json").timeout(HTTP_TIMEOUT_MS).execute()) {
            String raw = response.body();
            JsonNode root = JSONUtil.isTypeJSON(raw) ? MAPPER.readTree(raw) : null;
            int code = businessCode(root);
            if (!(response.getStatus() >= 200 && response.getStatus() < 300 && code == 0)) {
                log.warn("Provider upstream admin operation failed, providerId={}, httpStatus={}, code={}",
                    provider.getId(), response.getStatus(), code);
                throw new ServiceException(safeMessage(response.getStatus(), code));
            }
            return root;
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Provider upstream admin operation unavailable, providerId={}, error={}",
                provider.getId(), ex.getClass().getSimpleName());
            throw new ServiceException("上游服务暂不可用");
        }
    }

    private JsonNode requestMinimax(AidAiProvider provider, String url) {
        if (StrUtil.isBlank(provider.getApiKey())) {
            throw failure("missing MiniMax api key, providerId=" + provider.getId(), "API密钥未配置");
        }
        try (HttpResponse response = HttpRequest.get(url)
            .header("Authorization", "Bearer " + provider.getApiKey().trim())
            .header("Content-Type", "application/json")
            .timeout(HTTP_TIMEOUT_MS)
            .execute()) {
            String raw = response.body();
            JsonNode root = JSONUtil.isTypeJSON(raw) ? MAPPER.readTree(raw) : null;
            if (response.getStatus() < 200 || response.getStatus() >= 300 || root == null || !root.isObject()) {
                log.warn("MiniMax upstream task query failed, providerId={}, httpStatus={}, responseLength={}",
                    provider.getId(), response.getStatus(), StrUtil.length(raw));
                throw new ServiceException(response.getStatus() == 401
                    ? "上游鉴权配置无效" : "上游任务查询失败");
            }
            return root;
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("MiniMax upstream task query unavailable, providerId={}, error={}",
                provider.getId(), ex.getClass().getSimpleName());
            throw new ServiceException("上游服务暂不可用");
        }
    }

    private int businessCode(JsonNode root) {
        if (root == null || !root.has("code")) {
            return -1;
        }
        try {
            return Integer.parseInt(root.get("code").asText());
        } catch (Exception ex) {
            return -1;
        }
    }

    private AidAiProvider requireProvider(Long providerId) {
        AidAiProvider provider = providerId == null ? null : providerService.selectAidAiProviderById(providerId);
        if (provider == null) {
            throw failure("provider not found, providerId=" + providerId, "供应商不存在");
        }
        return provider;
    }

    private AidAiProvider requireKling(Long providerId) {
        AidAiProvider provider = requireProvider(providerId);
        if (!KLING_PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(provider.getProviderCode()))) {
            throw failure("unsupported provider operation, providerId=" + providerId, "供应商不支持");
        }
        buildTaskCollectionPath(provider.getTaskQuerySuffix());
        return provider;
    }

    private AidAiProvider requireMinimax(AidAiProvider provider) {
        if (provider == null
            || !MINIMAX_PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(provider.getProviderCode()))) {
            throw failure("unsupported MiniMax provider operation", "供应商不支持");
        }
        buildTaskCollectionPath(provider.getTaskQuerySuffix());
        return provider;
    }

    record MinimaxCursor(int page, int limit, List<String> statuses, List<String> taskIds,
                         String model, String taskType) {
        MinimaxCursor nextPage() {
            return new MinimaxCursor(page + 1, limit, statuses, taskIds, model, taskType);
        }
    }

    private record BalanceCache(String key, long createdAt, Map<String, Object> value) {
    }

    private static ServiceException failure(String reason, String clientMessage) {
        log.warn("Provider upstream operation rejected: {}", reason);
        return new ServiceException(clientMessage);
    }

    private String safeMessage(int httpStatus, int businessCode) {
        if (businessCode == 1301) return "输入内容未通过安全校验";
        if (businessCode == 1302 || businessCode == 1303 || httpStatus == 429) return "上游繁忙，请稍后重试";
        if (httpStatus == 401 || (businessCode >= 1000 && businessCode <= 1004)) return "上游鉴权配置无效";
        if (businessCode >= 1100 && businessCode <= 1103) return "上游账户或权限不可用";
        if (businessCode >= 1200 && businessCode <= 1203) return "上游请求参数不兼容";
        if (httpStatus >= 500 || (businessCode >= 5000 && businessCode <= 5002)) return "上游服务暂不可用";
        return "上游请求失败";
    }
}
