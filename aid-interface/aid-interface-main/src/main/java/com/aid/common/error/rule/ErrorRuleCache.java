package com.aid.common.error.rule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidProviderErrorRule;
import com.aid.aid.mapper.AidProviderErrorRuleMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * 错误规则缓存：内存 + 本地文件双层。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorRuleCache {

    private static final String LOCAL_CACHE_DIR = ".aid/cache";
    private static final String LOCAL_CACHE_FILE = "error-rules.json";

    private final AidProviderErrorRuleMapper errorRuleMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 内存缓存。key 形态：。
     */
    private volatile Map<String, List<AidProviderErrorRule>> bucketed = new HashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @PostConstruct
    public void init() {
        // 先从 DB 全量加载（保证拿到最新规则），本地文件仅作为 DB 不可用时的降级快照。
        // 否则一旦本地文件存在，直接经 SQL 写入 DB 的新规则会被旧快照永久遮蔽。
        try {
            rebuild();
            return;
        } catch (Exception e) {
            log.error("[ErrorRuleCache] 启动时从 DB 加载规则失败，尝试本地缓存文件降级", e);
        }
        if (loadFromLocalFile()) {
            log.info("[ErrorRuleCache] 从本地缓存文件加载规则成功（降级）");
        } else {
            log.error("[ErrorRuleCache] 本地缓存文件也不可用，运行时归一化将走兜底");
        }
    }

    /**
     * 业务调用：纯内存查询有效规则，三层合并并按 priority 升序返回。
     */
    public List<AidProviderErrorRule> findEffective(String providerCode, String modelCode) {
        lock.readLock().lock();
        try {
            List<AidProviderErrorRule> merged = new ArrayList<>();
            // 模型级
            if (StringUtils.isNotBlank(modelCode)) {
                List<AidProviderErrorRule> modelRules = bucketed.get("M:" + modelCode);
                if (modelRules != null) {
                    merged.addAll(modelRules);
                }
            }
            // 厂商级
            if (StringUtils.isNotBlank(providerCode)) {
                List<AidProviderErrorRule> providerRules = bucketed.get("P:" + providerCode);
                if (providerRules != null) {
                    merged.addAll(providerRules);
                }
            }
            // 全局
            List<AidProviderErrorRule> globalRules = bucketed.get("G");
            if (globalRules != null) {
                merged.addAll(globalRules);
            }
            // 按 priority 升序
            merged.sort(Comparator.comparingInt(r ->
                    r.getPriority() == null ? Integer.MAX_VALUE : r.getPriority()));
            return merged;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 增量刷新：仅重新加载该 providerCode 下的规则（含其下所有 modelCode 与 providerCode=NULL 全局规则）。
     * 后台保存规则后调用。
     */
    public void refresh(String providerCode) {
        // 简单起见直接全量重建：错误规则总量 <1000 行，重建成本 ms 级
        rebuild();
    }

    /**
     * 全量重建：从 DB 拉所有启用规则 → 重新分桶 → 写本地文件。
     */
    public void rebuild() {
        log.info("[ErrorRuleCache] 开始全量重建");
        LambdaQueryWrapper<AidProviderErrorRule> qw = new LambdaQueryWrapper<>();
        qw.eq(AidProviderErrorRule::getEnabled, 1);
        List<AidProviderErrorRule> all = errorRuleMapper.selectList(qw);
        Map<String, List<AidProviderErrorRule>> newBuckets = bucket(all);
        lock.writeLock().lock();
        try {
            this.bucketed = newBuckets;
        } finally {
            lock.writeLock().unlock();
        }
        try {
            writeLocalFile(all);
        } catch (Exception e) {
            log.warn("[ErrorRuleCache] 写本地缓存文件失败: {}", e.getMessage());
        }
        log.info("[ErrorRuleCache] 重建完成: 规则数={}, 分桶数={}", all.size(), newBuckets.size());
    }

    private Map<String, List<AidProviderErrorRule>> bucket(List<AidProviderErrorRule> rules) {
        Map<String, List<AidProviderErrorRule>> map = new HashMap<>();
        for (AidProviderErrorRule rule : rules) {
            String key = bucketKey(rule);
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
        }
        // 桶内预排序
        for (List<AidProviderErrorRule> list : map.values()) {
            list.sort(Comparator.comparingInt(r ->
                    r.getPriority() == null ? Integer.MAX_VALUE : r.getPriority()));
        }
        return map;
    }

    private String bucketKey(AidProviderErrorRule rule) {
        if (StringUtils.isNotBlank(rule.getModelCode())) {
            return "M:" + rule.getModelCode();
        }
        if (StringUtils.isNotBlank(rule.getProviderCode())) {
            return "P:" + rule.getProviderCode();
        }
        return "G";
    }

    /**
     * 读本地缓存文件 → 反序列化 → 分桶到内存。
     *
     * @return true 表示加载成功；false 表示文件不存在或损坏，需走 DB 加载
     */
    private boolean loadFromLocalFile() {
        File file = localCacheFile();
        if (!file.exists() || !file.isFile()) {
            log.info("[ErrorRuleCache] 本地缓存文件不存在: {}", file.getAbsolutePath());
            return false;
        }
        try {
            ErrorRuleCacheSnapshot snapshot = objectMapper.readValue(file, ErrorRuleCacheSnapshot.class);
            if (snapshot == null
                    || !Objects.equals(ErrorRuleCacheSnapshot.CURRENT_VERSION, snapshot.getVersion())) {
                log.warn("[ErrorRuleCache] 本地缓存版本不匹配, expected={}, actual={}",
                        ErrorRuleCacheSnapshot.CURRENT_VERSION,
                        snapshot == null ? null : snapshot.getVersion());
                return false;
            }
            List<AidProviderErrorRule> rules = snapshot.getRules() == null
                    ? new ArrayList<>()
                    : snapshot.getRules().stream()
                            .filter(r -> r.getEnabled() != null && r.getEnabled() == 1)
                            .collect(Collectors.toList());
            Map<String, List<AidProviderErrorRule>> newBuckets = bucket(rules);
            lock.writeLock().lock();
            try {
                this.bucketed = newBuckets;
            } finally {
                lock.writeLock().unlock();
            }
            log.info("[ErrorRuleCache] 本地缓存加载: 规则数={}", rules.size());
            return true;
        } catch (Exception e) {
            log.warn("[ErrorRuleCache] 本地缓存解析失败，回退 DB: {}", e.getMessage());
            return false;
        }
    }

    private void writeLocalFile(List<AidProviderErrorRule> rules) throws IOException {
        File file = localCacheFile();
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            Files.createDirectories(dir.toPath());
        }
        ErrorRuleCacheSnapshot snapshot = new ErrorRuleCacheSnapshot();
        snapshot.setVersion(ErrorRuleCacheSnapshot.CURRENT_VERSION);
        snapshot.setExportTime(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        snapshot.setRuleCount(rules.size());
        snapshot.setRules(rules);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, snapshot);
        log.info("[ErrorRuleCache] 本地缓存已写入: {}", file.getAbsolutePath());
    }

    private File localCacheFile() {
        String home = System.getProperty("user.home");
        return new File(home + File.separator + LOCAL_CACHE_DIR + File.separator + LOCAL_CACHE_FILE);
    }
}
