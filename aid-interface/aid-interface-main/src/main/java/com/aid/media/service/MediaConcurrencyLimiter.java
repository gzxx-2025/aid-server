package com.aid.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.AidConfig;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidConfigService;
import com.aid.media.enums.MediaTaskStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import cn.hutool.core.util.StrUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体任务并发限流服务：基于 Redis 原子计数实现「全局 → 用户 → 模型 → 供应商」四维并发准入。
 * 平台全局上限最大（所有任务之和不得超过），其下依次为用户级、模型级（所有人对该模型的在途请求）、
 * 供应商级（该供应商下所有模型的在途请求之和）。
 *
 * 配置来源：
 * - 全局 / 用户：aid_config（category=media）media_concurrent_limit_global / media_concurrent_limit_user；
 * - 模型：aid_ai_model.schedule_strategy_json.modelConcurrency（&lt;=0 或缺失 = 不限）；
 * - 供应商：aid_ai_provider.schedule_strategy_json.providerConcurrency（&lt;=0 或缺失 = 不限）。
 * 四类配置均带 5 秒本地缓存，后台修改后最迟 5 秒生效；计数在应用启动时从数据库重建自愈。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaConcurrencyLimiter {

    // Redis Key 前缀。
    private static final String KEY_GLOBAL = "media:concurrent:global";
    private static final String KEY_USER_PREFIX = "media:concurrent:user:";
    private static final String KEY_MODEL_PREFIX = "media:concurrent:model:";
    private static final String KEY_PROVIDER_PREFIX = "media:concurrent:provider:";

    /** 模型/供应商维度缺省占位桶（模型缺失、COMPOSE 等不在模型表的任务统一落此桶，按不限处理） */
    private static final String NONE_BUCKET = "none";

    // aid_config 中的配置键名。
    private static final String CONFIG_CATEGORY = "media";
    private static final String CONFIG_GLOBAL_LIMIT = "media_concurrent_limit_global";
    private static final String CONFIG_USER_LIMIT = "media_concurrent_limit_user";

    // 本地配置缓存刷新间隔（秒）。
    private static final long CONFIG_CACHE_TTL_SECONDS = 5L;

    // 默认限流阈值（aid_config 无记录时兜底）。
    private static final int DEFAULT_GLOBAL_LIMIT = 50;
    private static final int DEFAULT_USER_LIMIT = 5;

    /** 表示"不限制"的并发值（模型/供应商维度缺省不限） */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // 使用 StringRedisTemplate（纯字符串序列化），确保 Lua 脚本 tonumber() 正常工作。
    private final StringRedisTemplate stringRedisTemplate;
    private final AidMediaTaskMapper aidMediaTaskMapper;
    private final IAidConfigService aidConfigService;
    private final IAidAiModelService aidAiModelService;
    private final IAidAiProviderService aidAiProviderService;

    // Lua 脚本：四维原子 check + acquire。按平台 → 用户 → 模型 → 供应商顺序判定，全部有余量才一次性 INCR 四个计数。
    // 注意：用 `if g` 而非 `if g ~= false`，因为对不存在的 key 返回 nil 而非 false，
    // 而 Lua 中 `nil ~= false` 为 true 会导致 tonumber(nil) 比较报错。
    // 返回值：1=成功；-1=全局满；-2=用户满；-3=模型满；-4=供应商满。
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
        "local g = redis.call('GET', KEYS[1]) " +
        "if g and tonumber(g) >= tonumber(ARGV[1]) then return -1 end " +
        "local u = redis.call('GET', KEYS[2]) " +
        "if u and tonumber(u) >= tonumber(ARGV[2]) then return -2 end " +
        "local m = redis.call('GET', KEYS[3]) " +
        "if m and tonumber(m) >= tonumber(ARGV[3]) then return -3 end " +
        "local p = redis.call('GET', KEYS[4]) " +
        "if p and tonumber(p) >= tonumber(ARGV[4]) then return -4 end " +
        "redis.call('INCR', KEYS[1]) " +
        "redis.call('INCR', KEYS[2]) " +
        "redis.call('INCR', KEYS[3]) " +
        "redis.call('INCR', KEYS[4]) " +
        "return 1",
        Long.class
    );

    /**
     * 原子比较释放 Lua 脚本：仅当 current > 0 才 DECR，否则保持 0，
     * 避免计数被打到负数后永久绕过并发上限。
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
        "local v = redis.call('GET', KEYS[1]) " +
        "if not v then return 0 end " +
        "local n = tonumber(v) " +
        "if not n or n <= 0 then " +
        "  redis.call('SET', KEYS[1], '0') " +
        "  return 0 " +
        "end " +
        "return redis.call('DECR', KEYS[1])",
        Long.class
    );

    // 本地配置缓存：上次刷新时间 + 缓存值。
    private volatile long configCacheTime = 0;
    private volatile int cachedGlobalLimit = DEFAULT_GLOBAL_LIMIT;
    private volatile int cachedUserLimit = DEFAULT_USER_LIMIT;

    /** 模型元信息缓存：modelCode → [模型上限, providerId, 过期时刻]（5 秒本地缓存，减少 DB 查询） */
    private final Map<String, ModelMeta> modelMetaCache = new ConcurrentHashMap<>();

    /** 供应商上限缓存：providerId → [上限, 过期时刻] */
    private final Map<Long, long[]> providerLimitCache = new ConcurrentHashMap<>();

    /** 模型维度元信息：上限 + 所属供应商 ID + 缓存过期时刻。 */
    private static final class ModelMeta {
        final int modelLimit;
        final Long providerId;
        final long expireAt;

        ModelMeta(int modelLimit, Long providerId, long expireAt) {
            this.modelLimit = modelLimit;
            this.providerId = providerId;
            this.expireAt = expireAt;
        }
    }

    /**
     * 应用启动时从数据库修正 Redis 计数，防止 Redis 重启或 Key 丢失后计数不准。
     */
    @PostConstruct
    public void initRedisCounters() {
        try {
            rebuildCountersFromDb();
            log.info("媒体并发限流: Redis 计数已从数据库修正完成");
        } catch (Exception e) {
            // 启动时修正失败不影响主流程，仅打印警告。
            log.warn("媒体并发限流: Redis 计数修正失败，将以当前 Redis 值为准", e);
        }
    }

    /**
     * 尝试抢占四维并发坑位（不抛异常）。
     *
     * @param userId    用户 ID（匿名时可为 null）
     * @param modelCode 规范模型编码（aid_media_task.model_name；无法提供时可为 null，模型/供应商维度按不限处理）
     * @return true=抢占成功可立即提交，false=任一维度并发已满需排队
     */
    public boolean tryAcquire(Long userId, String modelCode) {
        int globalLimit = refreshConfig();
        int userLimit = getUserLimitValue();
        ModelMeta meta = resolveModelMeta(modelCode);
        int providerLimit = resolveProviderLimit(meta.providerId);

        // 使用 StringRedisTemplate 执行 Lua 脚本，ARGV 为纯字符串（不带 JSON 引号）。
        Long result = stringRedisTemplate.execute(
            ACQUIRE_SCRIPT,
            List.of(KEY_GLOBAL, resolveUserKey(userId), resolveModelKey(modelCode), resolveProviderKey(meta.providerId)),
            String.valueOf(globalLimit),
            String.valueOf(userLimit),
            String.valueOf(meta.modelLimit),
            String.valueOf(providerLimit)
        );

        return result != null && result == 1L;
    }

    /**
     * 任务终态时释放四维并发坑位（逐维原子 CAS 释放，计数到 0 后不再递减为负数）。
     * 释放的模型/供应商桶按当前配置解析：若任务在途期间运营改了模型所属供应商，可能出现单次维度偏差，
     * 由启动重建 {@link #rebuildCountersFromDb()} 自愈，且 CAS 地板保证不会出现负数。
     *
     * @param userId    用户 ID（匿名时可为 null）
     * @param modelCode 规范模型编码（aid_media_task.model_name，可为 null）
     */
    public void release(Long userId, String modelCode) {
        try {
            ModelMeta meta = resolveModelMeta(modelCode);
            stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(KEY_GLOBAL));
            stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(resolveUserKey(userId)));
            stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(resolveModelKey(modelCode)));
            stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(resolveProviderKey(meta.providerId)));
        } catch (Exception e) {
            // 释放失败仅打日志，不影响任务状态流转。
            log.warn("媒体并发限流: 释放坑位失败, userId={}, modelCode={}", userId, modelCode, e);
        }
    }

    /**
     * 查询指定排队任务在全局队列中的位置。
     *
     * @param userId 用户 ID（保留参数，当前按全局排序）
     * @param taskId 当前任务 ID
     * @return 排队位置（从 1 开始），不在排队中返回 null
     */
    public Integer getQueuePosition(Long userId, Long taskId) {
        if (taskId == null) {
            return null;
        }
        AidMediaTask current = aidMediaTaskMapper.selectById(taskId);
        if (current == null || !MediaTaskStatus.QUEUED.name().equals(current.getStatus())) {
            return null;
        }
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.QUEUED.name());
        wrapper.and(w -> w
            .lt(AidMediaTask::getCreateTime, current.getCreateTime())
            .or()
            .nested(n -> n
                .eq(AidMediaTask::getCreateTime, current.getCreateTime())
                .lt(AidMediaTask::getId, current.getId())
            )
        );
        Long ahead = aidMediaTaskMapper.selectCount(wrapper);
        return ahead.intValue() + 1;
    }

    /**
     * 查询系统当前总并发数。
     */
    public int getGlobalCount() {
        return readCounter(KEY_GLOBAL);
    }

    /**
     * 查询指定用户当前并发数。
     */
    public int getUserCount(Long userId) {
        if (userId == null) {
            return 0;
        }
        return readCounter(KEY_USER_PREFIX + userId);
    }

    /**
     * 查询指定模型当前在途请求数。
     */
    public int getModelCount(String modelCode) {
        if (StrUtil.isBlank(modelCode)) {
            return 0;
        }
        return readCounter(KEY_MODEL_PREFIX + modelCode);
    }

    /**
     * 查询指定供应商当前在途请求数。
     */
    public int getProviderCount(Long providerId) {
        if (providerId == null) {
            return 0;
        }
        return readCounter(KEY_PROVIDER_PREFIX + providerId);
    }

    /**
     * 获取系统全局并发上限。
     */
    public int getGlobalLimit() {
        refreshConfig();
        return cachedGlobalLimit;
    }

    /**
     * 获取单用户并发上限。
     */
    public int getUserLimitValue() {
        refreshConfig();
        return cachedUserLimit;
    }

    /**
     * 读取指定计数 Key 的当前值（缺失/异常按 0）。
     */
    private int readCounter(String key) {
        String val = stringRedisTemplate.opsForValue().get(key);
        if (val == null) {
            return 0;
        }
        try {
            int count = Integer.parseInt(val);
            return Math.max(count, 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 从数据库重建 Redis 计数器（启动时调用）。
     * 全局/用户/模型/供应商四维一并重建；重建后清理各前缀下未重建的残留 Key
     * （含匿名/IP 桶——其计数无法按 IP 从 DB 恢复，残留正数会把该 IP 永久限流，一并删除）。
     */
    private void rebuildCountersFromDb() {
        // 特别标注：本查询只取重建所需的最小字段（id + userId + modelName），新增依赖字段时须同步补充 select。
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(AidMediaTask::getId, AidMediaTask::getUserId, AidMediaTask::getModelName);
        // 查询所有 PENDING + PROCESSING + WAIT_POLL + WAIT_CALLBACK 任务（QUEUED 的不算并发）。
        wrapper.in(AidMediaTask::getStatus, MediaTaskStatus.PENDING.name(), MediaTaskStatus.PROCESSING.name(),
            MediaTaskStatus.WAIT_POLL.name(), MediaTaskStatus.WAIT_CALLBACK.name());
        List<AidMediaTask> activeTasks = aidMediaTaskMapper.selectList(wrapper);

        // 本次重建实际写入的 Key 集合，供后续清理"未重建的残留 Key"使用。
        Set<String> writtenKeys = new HashSet<>();

        int activeCount = activeTasks == null ? 0 : activeTasks.size();
        // 全局总数（无活跃任务时清零）。
        stringRedisTemplate.opsForValue().set(KEY_GLOBAL, String.valueOf(activeCount));

        if (activeCount > 0) {
            // 按用户 / 模型 / 供应商三维分组统计。
            Map<String, Integer> userCounts = new HashMap<>();
            Map<String, Integer> modelCounts = new HashMap<>();
            Map<String, Integer> providerCounts = new HashMap<>();
            for (AidMediaTask t : activeTasks) {
                if (t.getUserId() != null) {
                    userCounts.merge(KEY_USER_PREFIX + t.getUserId(), 1, Integer::sum);
                }
                modelCounts.merge(resolveModelKey(t.getModelName()), 1, Integer::sum);
                // 供应商按模型反查（带 5 秒缓存，重复模型不重复查库）。
                ModelMeta meta = resolveModelMeta(t.getModelName());
                providerCounts.merge(resolveProviderKey(meta.providerId), 1, Integer::sum);
            }
            writeCounters(userCounts, writtenKeys);
            writeCounters(modelCounts, writtenKeys);
            writeCounters(providerCounts, writtenKeys);
        }

        // 清理三个前缀下未重建的残留 Key（不含全局 Key，全局已覆盖写）。
        cleanupStaleKeys(KEY_USER_PREFIX, writtenKeys);
        cleanupStaleKeys(KEY_MODEL_PREFIX, writtenKeys);
        cleanupStaleKeys(KEY_PROVIDER_PREFIX, writtenKeys);

        log.info("媒体并发限流: 从数据库修正完成, globalCount={}, keyCount={}", activeCount, writtenKeys.size());
    }

    /**
     * 批量写入分组计数并登记到已写集合。
     */
    private void writeCounters(Map<String, Integer> counts, Set<String> writtenKeys) {
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            stringRedisTemplate.opsForValue().set(entry.getKey(), String.valueOf(entry.getValue()));
            writtenKeys.add(entry.getKey());
        }
    }

    /**
     * 清理指定前缀下本次重建未写入的残留 Key。
     * 用 SCAN 按批游标迭代替代 KEYS 通配符查询，避免大集群下 O(N) 阻塞。
     */
    private void cleanupStaleKeys(String prefix, Set<String> writtenKeys) {
        try {
            org.springframework.data.redis.core.ScanOptions options = org.springframework.data.redis.core.ScanOptions
                    .scanOptions()
                    .match(prefix + "*")
                    .count(200)
                    .build();
            try (org.springframework.data.redis.core.Cursor<String> cursor =
                         stringRedisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    if (key == null || !key.startsWith(prefix)) {
                        continue;
                    }
                    if (!writtenKeys.contains(key)) {
                        stringRedisTemplate.delete(key);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("媒体并发限流: 清理残留 Key 失败, prefix={}", prefix, e);
        }
    }

    /**
     * 刷新本地配置缓存（5 秒间隔从 aid_config 表读取）。
     */
    private int refreshConfig() {
        long now = System.currentTimeMillis();
        if (now - configCacheTime < CONFIG_CACHE_TTL_SECONDS * 1000L) {
            return cachedGlobalLimit;
        }
        try {
            cachedGlobalLimit = readConfigValue(CONFIG_GLOBAL_LIMIT, DEFAULT_GLOBAL_LIMIT);
            cachedUserLimit = readConfigValue(CONFIG_USER_LIMIT, DEFAULT_USER_LIMIT);
            configCacheTime = now;
        } catch (Exception e) {
            log.warn("媒体并发限流: 读取配置失败，使用缓存值", e);
        }
        return cachedGlobalLimit;
    }

    /**
     * 从 aid_config 表读取配置值，解析失败或未配置时返回默认值。
     */
    private int readConfigValue(String configName, int defaultValue) {
        try {
            LambdaQueryWrapper<AidConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AidConfig::getCategory, CONFIG_CATEGORY);
            wrapper.eq(AidConfig::getConfigName, configName);
            wrapper.select(AidConfig::getConfigValue);
            wrapper.last("limit 1");
            AidConfig config = aidConfigService.getOne(wrapper);
            if (config != null && config.getConfigValue() != null) {
                return Integer.parseInt(config.getConfigValue().trim());
            }
        } catch (Exception ignored) {
            // 配置不存在或解析失败时返回默认值。
        }
        return defaultValue;
    }

    /**
     * 解析模型维度元信息（上限 + 所属供应商 ID），带 5 秒本地缓存。
     * 模型不在 aid_ai_model（如 COMPOSE 合成任务）或编码为空 → 不限 + 无供应商。
     */
    private ModelMeta resolveModelMeta(String modelCode) {
        if (StrUtil.isBlank(modelCode)) {
            return new ModelMeta(UNLIMITED, null, 0L);
        }
        long now = System.currentTimeMillis();
        ModelMeta cached = modelMetaCache.get(modelCode);
        if (cached != null && cached.expireAt > now) {
            return cached;
        }
        int limit = UNLIMITED;
        Long providerId = null;
        try {
            // 特别标注：只查模型上限解析必要字段（model_code + provider_id + schedule_strategy_json）。
            AidAiModel model = aidAiModelService.getOne(
                    Wrappers.<AidAiModel>lambdaQuery()
                            .select(AidAiModel::getModelCode, AidAiModel::getProviderId, AidAiModel::getScheduleStrategyJson)
                            .eq(AidAiModel::getModelCode, modelCode)
                            .last("LIMIT 1"), false);
            if (model != null) {
                providerId = model.getProviderId();
                limit = parseConcurrencyField(model.getScheduleStrategyJson(), "modelConcurrency");
            }
        } catch (Exception e) {
            log.warn("媒体并发限流: 读取模型上限失败, modelCode={}, 默认不限: {}", modelCode, e.getMessage());
        }
        ModelMeta meta = new ModelMeta(limit, providerId, now + CONFIG_CACHE_TTL_SECONDS * 1000L);
        modelMetaCache.put(modelCode, meta);
        return meta;
    }

    /**
     * 解析供应商维度上限（aid_ai_provider.schedule_strategy_json.providerConcurrency），带 5 秒本地缓存。
     */
    private int resolveProviderLimit(Long providerId) {
        if (Objects.isNull(providerId)) {
            return UNLIMITED;
        }
        long now = System.currentTimeMillis();
        long[] cached = providerLimitCache.get(providerId);
        if (cached != null && cached[1] > now) {
            return (int) cached[0];
        }
        int limit = UNLIMITED;
        try {
            // 特别标注：只查供应商上限解析必要字段（id + schedule_strategy_json）。
            AidAiProvider provider = aidAiProviderService.getOne(
                    Wrappers.<AidAiProvider>lambdaQuery()
                            .select(AidAiProvider::getId, AidAiProvider::getScheduleStrategyJson)
                            .eq(AidAiProvider::getId, providerId)
                            .last("LIMIT 1"), false);
            if (provider != null) {
                limit = parseConcurrencyField(provider.getScheduleStrategyJson(), "providerConcurrency");
            }
        } catch (Exception e) {
            log.warn("媒体并发限流: 读取供应商上限失败, providerId={}, 默认不限: {}", providerId, e.getMessage());
        }
        providerLimitCache.put(providerId, new long[]{limit, now + CONFIG_CACHE_TTL_SECONDS * 1000L});
        return limit;
    }

    /**
     * 从 schedule_strategy_json 解析指定并发字段（缺省/非法/&lt;=0 视为不限制）。
     */
    private static int parseConcurrencyField(String scheduleStrategyJson, String fieldName) {
        if (StrUtil.isBlank(scheduleStrategyJson)) {
            return UNLIMITED;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(scheduleStrategyJson);
            JsonNode field = node.get(fieldName);
            if (field == null || !field.isNumber()) {
                return UNLIMITED;
            }
            int v = field.asInt();
            return v > 0 ? v : UNLIMITED;
        } catch (Exception e) {
            log.warn("媒体并发限流: 解析 schedule_strategy_json.{} 失败, 默认不限: {}", fieldName, e.getMessage());
            return UNLIMITED;
        }
    }

    /**
     * 根据用户 ID 拼接 Redis Key。
     * 匿名用户按线程上下文 clientIp 分桶，避免所有匿名请求共享同一上限，
     * 防止攻击者用大量匿名并发打满单一匿名桶导致合法匿名请求失败。
     */
    private String resolveUserKey(Long userId) {
        if (userId != null) {
            return KEY_USER_PREFIX + userId;
        }
        String ip = null;
        try {
            ip = com.aid.common.utils.ip.IpUtils.getIpAddr();
        } catch (Throwable ignore) {
            // ServletRequest 不存在时安静降级
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            return KEY_USER_PREFIX + "anonymous";
        }
        // 将 IP 中 '.' ':' 替换成 '_' 避免与其它 Redis 命名空间分隔符冲突
        String safeIp = ip.replace('.', '_').replace(':', '_');
        return KEY_USER_PREFIX + "anon:" + safeIp;
    }

    /** 模型维度计数 Key（编码为空 → none 占位桶）。 */
    private String resolveModelKey(String modelCode) {
        return KEY_MODEL_PREFIX + (StrUtil.isBlank(modelCode) ? NONE_BUCKET : modelCode);
    }

    /** 供应商维度计数 Key（providerId 为空 → none 占位桶）。 */
    private String resolveProviderKey(Long providerId) {
        return KEY_PROVIDER_PREFIX + (providerId == null ? NONE_BUCKET : providerId);
    }
}
