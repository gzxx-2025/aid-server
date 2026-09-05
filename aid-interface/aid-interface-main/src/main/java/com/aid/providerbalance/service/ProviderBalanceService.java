package com.aid.providerbalance.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.ProviderBalanceConfig;
import com.aid.aid.domain.ProviderBalanceDelivery;
import com.aid.aid.domain.ProviderBalanceIncident;
import com.aid.aid.domain.ProviderBalanceRecipient;
import com.aid.aid.domain.ProviderBalanceSnapshot;
import com.aid.aid.domain.ProviderCostLedger;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.mapper.ProviderBalanceConfigMapper;
import com.aid.aid.mapper.ProviderBalanceDeliveryMapper;
import com.aid.aid.mapper.ProviderBalanceIncidentMapper;
import com.aid.aid.mapper.ProviderBalanceRecipientMapper;
import com.aid.aid.mapper.ProviderBalanceSnapshotMapper;
import com.aid.aid.mapper.ProviderCostLedgerMapper;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidConfigService;
import com.aid.aid.service.IProviderUpstreamOperationsService;
import com.aid.billing.model.BillingSnapshot;
import com.aid.common.aid.mail.config.MailConfigManager;
import com.aid.common.aid.mail.core.MailTemplateFactory;
import com.aid.common.aid.sms.config.SmsConfigManager;
import com.aid.common.aid.sms.entity.SmsResult;
import com.aid.common.aid.sms.core.SmsTemplateFactory;
import com.aid.common.aid.wxlogin.core.WxLoginTemplateFactory;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.media.event.MediaTaskCompletedEvent;
import com.aid.notify.wechat.service.IWechatTemplateMessageSender;
import com.aid.notify.wechat.vo.WechatTemplatePayload;
import com.aid.notify.wechat.vo.WechatTemplateSendResult;
import com.aid.providerbalance.model.ProviderBalanceSettings;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** 供应商余额监控核心服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderBalanceService {
    private static final String CONFIG_CATEGORY = "provider_balance";
    private static final String CONFIG_KEY = "settings";
    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_WARNING = "WARNING";
    private static final String STATUS_CRITICAL = "CRITICAL";
    private static final String ERROR_TRIGGER_CACHE_PREFIX = "provider_balance:error_trigger:";
    private static final String CHECK_LOCK_CACHE_PREFIX = "provider_balance:check_lock:";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99999999");
    private volatile LocalDate lastCleanupDate;

    private final ProviderBalanceConfigMapper configMapper;
    private final ProviderBalanceRecipientMapper recipientMapper;
    private final ProviderBalanceSnapshotMapper snapshotMapper;
    private final ProviderBalanceIncidentMapper incidentMapper;
    private final ProviderBalanceDeliveryMapper deliveryMapper;
    private final ProviderCostLedgerMapper ledgerMapper;
    private final RedisCache redisCache;
    private final AidMediaTaskMapper mediaTaskMapper;
    private final IAidAiProviderService providerService;
    private final IAidAiModelService modelService;
    private final IAidConfigService aidConfigService;
    private final IProviderUpstreamOperationsService upstreamOperationsService;
    private final MailConfigManager mailConfigManager;
    private final MailTemplateFactory mailTemplateFactory;
    private final SmsConfigManager smsConfigManager;
    private final SmsTemplateFactory smsTemplateFactory;
    private final WxLoginTemplateFactory wxLoginTemplateFactory;
    private final IWechatTemplateMessageSender wechatSender;

    public ProviderBalanceSettings getSettings() {
        String raw = aidConfigService.getConfigValue(CONFIG_CATEGORY, CONFIG_KEY);
        if (StrUtil.isBlank(raw)) {
            return new ProviderBalanceSettings();
        }
        try {
            ProviderBalanceSettings settings = JSON.parseObject(raw, ProviderBalanceSettings.class);
            return settings == null ? new ProviderBalanceSettings() : settings;
        } catch (Exception ex) {
            log.error("供应商余额全局配置解析失败，按关闭处理", ex);
            return new ProviderBalanceSettings();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveSettings(ProviderBalanceSettings settings) {
        if (settings == null) {
            throw new ServiceException("配置不能为空");
        }
        boolean previouslyEnabled = getSettings().isEnabled();
        validateSettings(settings);
        aidConfigService.upsertConfigValue(CONFIG_CATEGORY, CONFIG_KEY, JSON.toJSONString(settings));
        if (previouslyEnabled && !settings.isEnabled()) {
            for (ProviderBalanceConfig config : configMapper.selectList(
                    Wrappers.<ProviderBalanceConfig>lambdaQuery().eq(ProviderBalanceConfig::getEnabled, 1))) {
                closeIncidentForDisabledProvider(config.getProviderId(), "system");
            }
        }
    }

    public Map<String, Object> overview() {
        List<Map<String, Object>> providers = listProviders();
        long monitored = providers.stream().filter(v -> Boolean.TRUE.equals(v.get("enabled"))).count();
        long warning = providers.stream().filter(v -> STATUS_WARNING.equals(v.get("currentStatus"))).count();
        long critical = providers.stream().filter(v -> STATUS_CRITICAL.equals(v.get("currentStatus"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settings", getSettings());
        result.put("channels", channelCapabilities());
        result.put("providerCount", providers.size());
        result.put("monitoredCount", monitored);
        result.put("warningCount", warning);
        result.put("criticalCount", critical);
        result.put("openIncidentCount", incidentMapper.selectCount(Wrappers.<ProviderBalanceIncident>lambdaQuery()
                .in(ProviderBalanceIncident::getStatus, "OPEN", "ACKED")));
        result.put("recipientCount", recipientMapper.selectCount(Wrappers.<ProviderBalanceRecipient>lambdaQuery()
                .eq(ProviderBalanceRecipient::getEnabled, 1)));
        return result;
    }

    public Map<String, Object> channelCapabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("EMAIL", capability(mailConfigManager.isEnabled(), true, "请先在系统配置中启用邮箱服务"));
        result.put("SMS", capability(smsConfigManager.isEnabled(),
                StrUtil.isNotBlank(getSettings().getSmsTemplateId()), "请先在系统配置中启用短信服务"));
        result.put("WECHAT", capability(wxLoginTemplateFactory.isEnabled(),
                StrUtil.isNotBlank(getSettings().getWechatTemplateId()), "请先启用微信公众号扫码配置"));
        return result;
    }

    private Map<String, Object> capability(boolean enabled, boolean templateReady, String disabledReason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("enabled", enabled);
        item.put("templateReady", templateReady);
        item.put("disabledReason", enabled ? "" : disabledReason);
        return item;
    }

    public List<Map<String, Object>> listProviders() {
        List<AidAiProvider> providers = providerService.selectAidAiProviderList(new AidAiProvider());
        Map<Long, ProviderBalanceConfig> configMap = configMapper.selectList(null).stream()
                .collect(Collectors.toMap(ProviderBalanceConfig::getProviderId, v -> v, (a, b) -> a));
        List<Map<String, Object>> result = new ArrayList<>();
        for (AidAiProvider provider : providers) {
            ProviderBalanceConfig config = configMap.get(provider.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("providerId", provider.getId());
            item.put("providerCode", provider.getProviderCode());
            item.put("providerName", provider.getProviderName());
            item.put("logoUrl", provider.getLogoUrl());
            item.put("providerStatus", provider.getStatus());
            item.put("apiSupported", isApiSupported(provider.getId()));
            item.put("apiBalanceUnit", upstreamBalanceUnit(provider.getId()));
            if (config == null) {
                item.putAll(defaultProviderValues());
            } else {
                item.putAll(JSON.parseObject(JSON.toJSONString(config), Map.class));
                item.put("enabled", Objects.equals(config.getEnabled(), 1));
                item.put("apiEnabled", Objects.equals(config.getApiEnabled(), 1));
                item.put("simulatedEnabled", Objects.equals(config.getSimulatedEnabled(), 1));
                item.put("errorRuleEnabled", Objects.equals(config.getErrorRuleEnabled(), 1));
                item.put("forecastEnabled", Objects.equals(config.getForecastEnabled(), 1));
                item.put("runwayDays", calculateRunwayDays(config));
            }
            result.add(item);
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveProviderConfig(Long providerId, ProviderBalanceConfig input, String username) {
        AidAiProvider provider = providerService.selectAidAiProviderById(providerId);
        if (provider == null) {
            throw new ServiceException("供应商不存在");
        }
        if (input == null) {
            throw new ServiceException("供应商监控配置不能为空");
        }
        normalizeAndValidateProvider(input, providerId);
        ProviderBalanceConfig existing = findConfig(providerId);
        Date now = DateUtils.getNowDate();
        input.setProviderId(providerId);
        input.setUpdateBy(username);
        input.setUpdateTime(now);
        if (existing == null) {
            input.setCreateBy(username);
            input.setCreateTime(now);
            input.setCurrentStatus(STATUS_NORMAL);
            input.setConsecutiveLow(0);
            configMapper.insert(input);
        } else {
            input.setId(existing.getId());
            input.setCreateBy(existing.getCreateBy());
            input.setCreateTime(existing.getCreateTime());
            input.setCurrentStatus(Objects.equals(input.getEnabled(), 1)
                    ? existing.getCurrentStatus() : STATUS_NORMAL);
            input.setCurrentSource(existing.getCurrentSource());
            input.setCurrentBalance(existing.getCurrentBalance());
            input.setSimulatedBalance(existing.getSimulatedBalance());
            input.setLastCheckTime(existing.getLastCheckTime());
            input.setLastSuccessTime(existing.getLastSuccessTime());
            input.setLastError(existing.getLastError());
            input.setConsecutiveLow(Objects.equals(input.getEnabled(), 1)
                    ? existing.getConsecutiveLow() : 0);
            input.setSilenceUntil(Objects.equals(input.getEnabled(), 1)
                    ? existing.getSilenceUntil() : null);
            configMapper.updateById(input);
        }
        if (!Objects.equals(input.getEnabled(), 1)) {
            closeIncidentForDisabledProvider(providerId, username);
        }
        validateRunningConfiguration();
    }

    public List<Map<String, Object>> listRecipients() {
        return recipientMapper.selectList(Wrappers.<ProviderBalanceRecipient>lambdaQuery()
                        .orderByDesc(ProviderBalanceRecipient::getId)).stream()
                .map(this::recipientView).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public Long saveRecipient(ProviderBalanceRecipient input, String targetValue, String username) {
        if (input == null || StrUtil.isBlank(input.getChannel()) || StrUtil.isBlank(input.getRecipientName())) {
            throw new ServiceException("提醒人信息不完整");
        }
        String channel = input.getChannel().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("EMAIL", "SMS").contains(channel)) {
            throw new ServiceException("微信提醒人请通过扫码添加");
        }
        assertChannelEnabled(channel);
        ProviderBalanceRecipient existing = input.getId() == null ? null : recipientMapper.selectById(input.getId());
        String target = StrUtil.trim(targetValue);
        if (existing != null && StrUtil.isBlank(target)) {
            target = existing.getTargetValue();
        }
        validateTarget(channel, target);
        validateProviderIds(input.getProviderIds());
        Date now = DateUtils.getNowDate();
        input.setRecipientName(safeText(input.getRecipientName(), 64));
        input.setChannel(channel);
        input.setTargetValue(target);
        input.setTargetHash(sha256(channel + ":" + target.toLowerCase(Locale.ROOT)));
        input.setDisplayValue(maskTarget(channel, target));
        input.setEnabled(Objects.equals(input.getEnabled(), 0) ? 0 : 1);
        input.setDailyReportEnabled("EMAIL".equals(channel) && Objects.equals(input.getDailyReportEnabled(), 1) ? 1 : 0);
        input.setUpdateBy(username);
        input.setUpdateTime(now);
        try {
            if (existing == null) {
                input.setId(null);
                input.setCreateBy(username);
                input.setCreateTime(now);
                recipientMapper.insert(input);
            } else {
                input.setCreateBy(existing.getCreateBy());
                input.setCreateTime(existing.getCreateTime());
                recipientMapper.updateById(input);
            }
        } catch (DuplicateKeyException ex) {
            throw new ServiceException("该接收地址已存在");
        }
        validateRunningConfiguration();
        return input.getId();
    }

    /** 微信扫码回调专用：按 OpenID 幂等新增或恢复提醒人。 */
    @Transactional(rollbackFor = Exception.class)
    public Long saveWechatRecipient(String recipientName, String openId, String nickname,
                                    String providerIds, String username) {
        assertChannelEnabled("WECHAT");
        if (StrUtil.isBlank(openId)) {
            throw new ServiceException("微信 OpenID 为空");
        }
        validateProviderIds(providerIds);
        String hash = sha256("WECHAT:" + openId);
        ProviderBalanceRecipient recipient = recipientMapper.selectOne(
                Wrappers.<ProviderBalanceRecipient>lambdaQuery()
                        .eq(ProviderBalanceRecipient::getTargetHash, hash).last("limit 1"));
        Date now = new Date();
        if (recipient == null) {
            recipient = new ProviderBalanceRecipient();
            recipient.setChannel("WECHAT");
            recipient.setTargetValue(openId);
            recipient.setTargetHash(hash);
            recipient.setDisplayValue(maskTarget("WECHAT", openId));
            recipient.setCreateBy(username);
            recipient.setCreateTime(now);
        }
        recipient.setRecipientName(StrUtil.blankToDefault(safeText(recipientName, 64),
                StrUtil.blankToDefault(safeText(nickname, 64), "微信提醒人")));
        recipient.setWechatNickname(safeText(nickname, 64));
        recipient.setProviderIds(providerIds);
        recipient.setEnabled(1);
        recipient.setDailyReportEnabled(0);
        recipient.setUpdateBy(username);
        recipient.setUpdateTime(now);
        if (recipient.getId() == null) recipientMapper.insert(recipient); else recipientMapper.updateById(recipient);
        validateRunningConfiguration();
        return recipient.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeRecipients(List<Long> ids) {
        if (ids != null && !ids.isEmpty()) {
            recipientMapper.deleteBatchIds(ids);
            validateRunningConfiguration();
        }
    }

    public List<ProviderBalanceIncident> listIncidents(Long providerId, String status) {
        return incidentMapper.selectList(Wrappers.<ProviderBalanceIncident>lambdaQuery()
                .eq(providerId != null, ProviderBalanceIncident::getProviderId, providerId)
                .eq(StrUtil.isNotBlank(status), ProviderBalanceIncident::getStatus, status)
                .orderByDesc(ProviderBalanceIncident::getId));
    }

    public List<ProviderBalanceDelivery> listDeliveries(Long incidentId, String status) {
        return deliveryMapper.selectList(Wrappers.<ProviderBalanceDelivery>lambdaQuery()
                .eq(incidentId != null, ProviderBalanceDelivery::getIncidentId, incidentId)
                .eq(StrUtil.isNotBlank(status), ProviderBalanceDelivery::getStatus, status)
                .orderByDesc(ProviderBalanceDelivery::getId));
    }

    public void acknowledge(Long incidentId, String username, Integer silenceMinutes) {
        ProviderBalanceIncident incident = incidentMapper.selectById(incidentId);
        if (incident == null || "RESOLVED".equals(incident.getStatus())) {
            throw new ServiceException("告警事件不存在或已恢复");
        }
        incident.setStatus("ACKED");
        incident.setAcknowledgedAt(new Date());
        incident.setAcknowledgedBy(username);
        incident.setUpdateBy(username);
        incident.setUpdateTime(new Date());
        incidentMapper.updateById(incident);
        if (silenceMinutes != null && silenceMinutes > 0) {
            ProviderBalanceConfig config = findConfig(incident.getProviderId());
            if (config != null) {
                config.setSilenceUntil(new Date(System.currentTimeMillis() + Math.min(silenceMinutes, 10080) * 60_000L));
                config.setUpdateBy(username);
                config.setUpdateTime(new Date());
                configMapper.updateById(config);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void addAdjustment(Long providerId, BigDecimal amount, String type, String remark, String username) {
        ProviderBalanceConfig config = findConfig(providerId);
        if (config == null || !Objects.equals(config.getSimulatedEnabled(), 1)) {
            throw new ServiceException("该供应商未开启模拟余额");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new ServiceException("调整金额不能为 0");
        }
        if (amount.abs().compareTo(MAX_AMOUNT) > 0) {
            throw new ServiceException("调整金额超出范围");
        }
        String entryType = "TOPUP".equalsIgnoreCase(type) ? "TOPUP" : "ADJUSTMENT";
        ProviderCostLedger ledger = new ProviderCostLedger();
        ledger.setEventKey(entryType + ":" + providerId + ":" + UUID.randomUUID());
        ledger.setProviderId(providerId);
        ledger.setEntryType(entryType);
        ledger.setAmount(amount.abs());
        ledger.setBalanceDelta(amount);
        ledger.setCurrency(config.getCurrency());
        ledger.setPrecisionType("MANUAL");
        ledger.setOccurredAt(new Date());
        ledger.setDetailJson(JSON.toJSONString(Map.of("remark", safeText(remark, 200))));
        ledger.setCreateBy(username);
        ledger.setCreateTime(new Date());
        ledgerMapper.insert(ledger);
    }

    /** 定时任务入口。任何单个供应商或通道失败均不能中断整轮。 */
    public void tick() {
        ProviderBalanceSettings settings = getSettings();
        if (!settings.isEnabled()) {
            return;
        }
        List<ProviderBalanceConfig> configs = configMapper.selectList(Wrappers.<ProviderBalanceConfig>lambdaQuery()
                .eq(ProviderBalanceConfig::getEnabled, 1));
        for (ProviderBalanceConfig config : configs) {
            try {
                if (isCheckDue(config)) {
                    checkProvider(config.getProviderId());
                }
                notifyDueIncident(config);
            } catch (Exception ex) {
                log.error("供应商余额检查失败: providerId={}", config.getProviderId(), ex);
            }
        }
        try {
            sendDailyReportIfDue(settings);
        } catch (Exception ex) {
            log.error("供应商余额日报处理失败", ex);
        }
        cleanupHistoryIfDue(settings);
    }

    public Map<String, Object> checkProvider(Long providerId) {
        String lockKey = CHECK_LOCK_CACHE_PREFIX + providerId;
        String lockToken = UUID.randomUUID().toString();
        boolean locked = false;
        try {
            locked = redisCache.setCacheObjectIfAbsent(lockKey, lockToken, 120, TimeUnit.SECONDS);
            if (!locked) {
                throw new ServiceException("该供应商余额正在检查");
            }
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("供应商余额检查锁不可用，继续执行。providerId={}", providerId, ex);
        }
        try {
            return doCheckProvider(providerId);
        } finally {
            if (locked) {
                try {
                    redisCache.deleteObjectIfValueEquals(lockKey, lockToken);
                } catch (Exception ex) {
                    log.warn("供应商余额检查锁释放失败。providerId={}", providerId, ex);
                }
            }
        }
    }

    private Map<String, Object> doCheckProvider(Long providerId) {
        if (!getSettings().isEnabled()) {
            throw new ServiceException("余额监控当前已关闭");
        }
        ProviderBalanceConfig config = findConfig(providerId);
        if (config == null || !Objects.equals(config.getEnabled(), 1)) {
            throw new ServiceException("该供应商未选择监控");
        }
        BigDecimal balance = null;
        String source = null;
        String queryError = null;
        if (Objects.equals(config.getApiEnabled(), 1) && isApiSupported(providerId)) {
            try {
                Map<String, Object> raw = upstreamOperationsService.balance(providerId, null, null, null);
                balance = extractBalance(raw);
                if (balance == null) {
                    throw new ServiceException("官方接口未返回可汇总余额");
                }
                source = "API";
                saveSnapshot(config, balance, source, "SUCCESS", "EXACT", raw, null);
            } catch (Exception ex) {
                queryError = safeText(ex.getMessage(), 200);
                saveSnapshot(config, null, "API", "UNAVAILABLE", "UNKNOWN", null, queryError);
            }
        }
        if (Objects.equals(config.getSimulatedEnabled(), 1) || Objects.equals(config.getForecastEnabled(), 1)) {
            reconcileProviderCosts(config);
        }
        BigDecimal simulated = null;
        if (Objects.equals(config.getSimulatedEnabled(), 1)) {
            simulated = calculateSimulatedBalance(config);
            config.setSimulatedBalance(simulated);
            saveSnapshot(config, simulated, "SIMULATED", "SUCCESS", "ESTIMATED", null, null);
            if (balance == null) {
                balance = simulated;
                source = "SIMULATED";
            }
        } else {
            config.setSimulatedBalance(null);
        }
        config.setCurrentBalance(balance);
        config.setCurrentSource(source);
        config.setLastCheckTime(new Date());
        config.setLastError(queryError);
        if (balance != null) {
            config.setLastSuccessTime(new Date());
            evaluateNumericBalance(config, balance, source);
        }
        persistCheckState(config);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerId", providerId);
        result.put("balance", balance);
        result.put("simulatedBalance", simulated);
        result.put("source", source);
        result.put("status", config.getCurrentStatus());
        result.put("queryError", queryError);
        return result;
    }

    /** 真实请求被明确余额不足规则命中时调用；只处理已勾选且开启错误规则检测的供应商。 */
    @Transactional(rollbackFor = Exception.class)
    public void handleBalanceError(String providerCode, String modelCode, String taskId) {
        if (!getSettings().isEnabled() || StrUtil.isBlank(providerCode)) {
            return;
        }
        AidAiProvider provider = providerService.getOne(Wrappers.<AidAiProvider>lambdaQuery()
                .eq(AidAiProvider::getProviderCode, providerCode).last("limit 1"));
        if (provider == null) {
            return;
        }
        ProviderBalanceConfig config = findConfig(provider.getId());
        if (config == null || !Objects.equals(config.getEnabled(), 1)
                || !Objects.equals(config.getErrorRuleEnabled(), 1)) {
            return;
        }
        try {
            if (!redisCache.setCacheObjectIfAbsent(ERROR_TRIGGER_CACHE_PREFIX + provider.getId(), "1", 15,
                    TimeUnit.SECONDS)) {
                return;
            }
        } catch (Exception ex) {
            log.warn("供应商余额错误去重不可用，继续记录。providerId={}", provider.getId(), ex);
        }
        config = findConfigForUpdate(provider.getId());
        if (config == null || !Objects.equals(config.getEnabled(), 1)
                || !Objects.equals(config.getErrorRuleEnabled(), 1)) {
            return;
        }
        String reason = "供应商返回余额不足" + (StrUtil.isBlank(modelCode) ? "" : "，触发模型：" + safeText(modelCode, 80));
        config.setCurrentStatus(STATUS_CRITICAL);
        config.setCurrentSource("ERROR_RULE");
        config.setCurrentBalance(null);
        config.setLastCheckTime(new Date());
        config.setLastError(reason);
        configMapper.update(null, Wrappers.<ProviderBalanceConfig>lambdaUpdate()
                .eq(ProviderBalanceConfig::getId, config.getId())
                .set(ProviderBalanceConfig::getCurrentStatus, STATUS_CRITICAL)
                .set(ProviderBalanceConfig::getCurrentSource, "ERROR_RULE")
                .set(ProviderBalanceConfig::getCurrentBalance, null)
                .set(ProviderBalanceConfig::getLastCheckTime, config.getLastCheckTime())
                .set(ProviderBalanceConfig::getLastError, reason));
        openOrUpdateIncident(config, STATUS_CRITICAL, "ERROR_RULE", null,
                config.getCriticalThreshold(), reason, false);
    }

    public void testRecipient(Long recipientId) {
        ProviderBalanceRecipient recipient = recipientMapper.selectById(recipientId);
        if (recipient == null) {
            throw new ServiceException("提醒人不存在");
        }
        assertChannelEnabled(recipient.getChannel());
        DeliveryOutcome outcome = sendOne(recipient, null, null, "TEST", "余额监控测试",
                "这是一条供应商余额监控测试消息，收到此消息表示通道配置可用。", null);
        recordDelivery(null, null, recipient, "TEST", outcome);
        if (!outcome.success()) {
            log.info("供应商余额提醒通道测试失败: recipientId={}, reason={}", recipientId, outcome.error());
            throw new ServiceException("测试发送失败");
        }
    }

    /** 成功终态事件在事务提交后产生，台账写入失败只记录日志，不反向影响用户任务。 */
    @EventListener
    public void onMediaTaskCompleted(MediaTaskCompletedEvent event) {
        if (!getSettings().isEnabled()) {
            return;
        }
        try {
            recordTaskCost(event.getTaskId());
        } catch (Exception ex) {
            log.error("供应商理论成本记录失败: taskId={}", event.getTaskId(), ex);
        }
    }

    private void recordTaskCost(Long taskId) {
        if (taskId == null || ledgerMapper.selectCount(Wrappers.<ProviderCostLedger>lambdaQuery()
                .eq(ProviderCostLedger::getEventKey, "MEDIA_TASK_SUCCESS:" + taskId)) > 0) {
            return;
        }
        AidMediaTask task = mediaTaskMapper.selectById(taskId);
        if (task == null || !"SUCCEEDED".equals(task.getStatus()) || StrUtil.isBlank(task.getBillingSnapshotJson())) {
            return;
        }
        BillingSnapshot snapshot;
        try {
            snapshot = JSON.parseObject(task.getBillingSnapshotJson(), BillingSnapshot.class);
        } catch (Exception ex) {
            return;
        }
        AidAiModel model = snapshot.getModelId() == null ? null : modelService.getById(snapshot.getModelId());
        if (model == null) {
            model = modelService.getOne(Wrappers.<AidAiModel>lambdaQuery()
                    .and(w -> w.eq(AidAiModel::getModelCode, task.getModelName())
                            .or().eq(AidAiModel::getRealModelCode, task.getModelName())).last("limit 1"));
        }
        if (model == null || model.getProviderId() == null) {
            return;
        }
        ProviderBalanceConfig config = findConfig(model.getProviderId());
        Date occurredAt = task.getTerminalTime() == null ? task.getUpdateTime() : task.getTerminalTime();
        if (config == null || !Objects.equals(config.getEnabled(), 1)
                || (!Objects.equals(config.getSimulatedEnabled(), 1)
                    && !Objects.equals(config.getForecastEnabled(), 1))
                || config.getInitialTime() == null || occurredAt == null || occurredAt.before(config.getInitialTime())) {
            return;
        }
        BigDecimal officialBaseAmount = theoreticalCost(snapshot);
        if (officialBaseAmount == null || officialBaseAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal convertedAmount = normalize(officialBaseAmount.multiply(
                config.getCostUnitMultiplier() == null ? BigDecimal.ONE : config.getCostUnitMultiplier()));
        ProviderCostLedger ledger = new ProviderCostLedger();
        ledger.setEventKey("MEDIA_TASK_SUCCESS:" + taskId);
        ledger.setProviderId(model.getProviderId());
        ledger.setModelId(model.getId());
        ledger.setTaskId(taskId);
        ledger.setModelCode(model.getModelCode());
        ledger.setEntryType("COST");
        ledger.setAmount(officialBaseAmount);
        ledger.setBalanceDelta(convertedAmount.negate());
        ledger.setCurrency(config.getCurrency());
        ledger.setPrecisionType(Boolean.TRUE.equals(snapshot.getProviderUsageCaptured()) ? "EXACT" : "ESTIMATED");
        ledger.setPricingVersion(snapshot.getBillingVersion() == null ? null : String.valueOf(snapshot.getBillingVersion()));
        ledger.setOccurredAt(occurredAt);
        ledger.setDetailJson(JSON.toJSONString(Map.of(
                "meterType", StrUtil.blankToDefault(snapshot.getMeterType(), "UNKNOWN"),
                "officialBaseAmount", officialBaseAmount,
                "costUnitMultiplier", config.getCostUnitMultiplier() == null ? BigDecimal.ONE : config.getCostUnitMultiplier())));
        ledger.setCreateBy("system");
        ledger.setCreateTime(new Date());
        try {
            ledgerMapper.insert(ledger);
        } catch (DuplicateKeyException ignored) {
            // 多个完成事件按 event_key 幂等收口。
        }
    }

    private BigDecimal theoreticalCost(BillingSnapshot snapshot) {
        BigDecimal inputMedia = value(snapshot.getInputMediaAmount());
        if ("PER_IMAGE".equals(snapshot.getMeterType()) && snapshot.getUnitPrice() != null) {
            Integer count = snapshot.getSettledImageCount() != null
                    ? snapshot.getSettledImageCount() : snapshot.getActualImageCount();
            if (count != null) {
                return normalize(snapshot.getUnitPrice().multiply(BigDecimal.valueOf(Math.max(count, 0))).add(inputMedia));
            }
        }
        if ("PER_SECOND".equals(snapshot.getMeterType()) && snapshot.getPricePerSecond() != null
                && snapshot.getActualDurationSeconds() != null) {
            return normalize(snapshot.getPricePerSecond()
                    .multiply(BigDecimal.valueOf(Math.max(snapshot.getActualDurationSeconds(), 0))).add(inputMedia));
        }
        return snapshot.getBaseAmount() == null ? null : normalize(snapshot.getBaseAmount());
    }

    private void evaluateNumericBalance(ProviderBalanceConfig config, BigDecimal balance, String source) {
        String severity = STATUS_NORMAL;
        BigDecimal threshold = null;
        String reason = null;
        if (config.getCriticalThreshold() != null && balance.compareTo(config.getCriticalThreshold()) <= 0) {
            severity = STATUS_CRITICAL;
            threshold = config.getCriticalThreshold();
            reason = "余额低于严重阈值";
        } else if (config.getWarningThreshold() != null && balance.compareTo(config.getWarningThreshold()) <= 0) {
            severity = STATUS_WARNING;
            threshold = config.getWarningThreshold();
            reason = "余额低于预警阈值";
        } else {
            BigDecimal runwayDays = calculateRunwayDays(config);
            if (Objects.equals(config.getForecastEnabled(), 1) && runwayDays != null
                    && runwayDays.compareTo(BigDecimal.valueOf(config.getForecastDays())) <= 0) {
                severity = STATUS_WARNING;
                source = "FORECAST";
                reason = "按近7日理论成本预计仅可用" + runwayDays.toPlainString() + "天";
            }
        }
        if (STATUS_NORMAL.equals(severity)) {
            config.setConsecutiveLow(0);
            ProviderBalanceIncident incident = currentIncident(config.getProviderId());
            BigDecimal recovery = config.getRecoveryThreshold() == null
                    ? config.getWarningThreshold() : config.getRecoveryThreshold();
            if (incident != null && recovery != null && balance.compareTo(recovery) < 0) {
                config.setCurrentStatus(incident.getSeverity());
                configMapper.updateById(config);
                return;
            }
            config.setCurrentStatus(STATUS_NORMAL);
            configMapper.updateById(config);
            resolveIncidentIfRecovered(config, balance, source);
            return;
        }
        int lowCount = Objects.requireNonNullElse(config.getConsecutiveLow(), 0) + 1;
        config.setConsecutiveLow(lowCount);
        config.setCurrentStatus(severity);
        configMapper.updateById(config);
        int confirm = Math.max(1, Objects.requireNonNullElse(config.getConfirmCount(), 2));
        if (lowCount >= confirm) {
            openOrUpdateIncident(config, severity, source, balance, threshold, reason, true);
        }
    }

    private void openOrUpdateIncident(ProviderBalanceConfig config, String severity, String source,
                                      BigDecimal balance, BigDecimal threshold, String reason,
                                      boolean dispatchImmediately) {
        ProviderBalanceIncident incident = currentIncident(config.getProviderId());
        Date now = new Date();
        boolean escalated = incident != null && STATUS_WARNING.equals(incident.getSeverity())
                && STATUS_CRITICAL.equals(severity);
        if (incident == null) {
            incident = new ProviderBalanceIncident();
            incident.setProviderId(config.getProviderId());
            incident.setStatus("OPEN");
            incident.setOpenedAt(now);
            incident.setCreateBy("system");
            incident.setCreateTime(now);
        } else if (escalated) {
            incident.setStatus("OPEN");
            incident.setAcknowledgedAt(null);
            incident.setAcknowledgedBy(null);
            incident.setLastNotifiedAt(null);
            incident.setNextNotifyAt(null);
            clearIncidentNotificationState(incident.getId());
        }
        incident.setSeverity(severity);
        incident.setTriggerSource(source);
        incident.setBalance(balance);
        incident.setThresholdAmount(threshold);
        incident.setCurrency(config.getCurrency());
        incident.setReason(safeText(reason, 300));
        incident.setLastTriggeredAt(now);
        incident.setUpdateBy("system");
        incident.setUpdateTime(now);
        boolean created = incident.getId() == null;
        boolean due = created || escalated || incident.getNextNotifyAt() == null
                || !incident.getNextNotifyAt().after(now);
        if (incident.getId() == null) {
            try {
                incidentMapper.insert(incident);
            } catch (DuplicateKeyException ex) {
                // 数值检查与真实请求错误同时触发时，由活动事件唯一键收口并锁定合并。
                incident = currentIncidentForUpdate(config.getProviderId());
                if (incident == null) {
                    throw ex;
                }
                escalated = STATUS_WARNING.equals(incident.getSeverity()) && STATUS_CRITICAL.equals(severity);
                if (escalated) {
                    incident.setStatus("OPEN");
                    incident.setAcknowledgedAt(null);
                    incident.setAcknowledgedBy(null);
                    incident.setLastNotifiedAt(null);
                    incident.setNextNotifyAt(null);
                    clearIncidentNotificationState(incident.getId());
                }
                incident.setSeverity(severity);
                incident.setTriggerSource(source);
                incident.setBalance(balance);
                incident.setThresholdAmount(threshold);
                incident.setCurrency(config.getCurrency());
                incident.setReason(safeText(reason, 300));
                incident.setLastTriggeredAt(now);
                incident.setUpdateBy("system");
                incident.setUpdateTime(now);
                due = escalated || incident.getNextNotifyAt() == null || !incident.getNextNotifyAt().after(now);
                incidentMapper.updateById(incident);
            }
        } else {
            incidentMapper.updateById(incident);
        }
        if (dispatchImmediately && due && !isSilenced(config)) {
            sendIncident(incident, config, false);
        }
    }

    private void notifyDueIncident(ProviderBalanceConfig config) {
        ProviderBalanceIncident incident = currentIncident(config.getProviderId());
        if (incident != null && !isSilenced(config)
                && (incident.getNextNotifyAt() == null || !incident.getNextNotifyAt().after(new Date()))) {
            sendIncident(incident, config, false);
        }
    }

    private void resolveIncidentIfRecovered(ProviderBalanceConfig config, BigDecimal balance, String source) {
        ProviderBalanceIncident incident = currentIncident(config.getProviderId());
        BigDecimal recovery = config.getRecoveryThreshold() == null ? config.getWarningThreshold() : config.getRecoveryThreshold();
        if (incident == null || recovery == null || balance.compareTo(recovery) < 0) {
            return;
        }
        incident.setStatus("RESOLVED");
        incident.setBalance(balance);
        incident.setTriggerSource(source);
        incident.setResolvedAt(new Date());
        incident.setUpdateBy("system");
        incident.setUpdateTime(new Date());
        incidentMapper.updateById(incident);
        sendIncident(incident, config, true);
    }

    private void sendIncident(ProviderBalanceIncident incident, ProviderBalanceConfig config, boolean recovery) {
        AidAiProvider provider = providerService.selectAidAiProviderById(config.getProviderId());
        if (provider == null) {
            return;
        }
        List<ProviderBalanceRecipient> recipients = activeRecipients(config.getProviderId(), false);
        int successCount = 0;
        int failureCount = 0;
        Date cycleStarted = new Date();
        int repeatMinutes = Math.max(5, Objects.requireNonNullElse(config.getRepeatIntervalMinutes(),
                getSettings().getDefaultRepeatIntervalMinutes()));
        boolean retryOnly = !recovery && incident.getLastNotifiedAt() != null
                && incident.getLastNotifiedAt().getTime() + repeatMinutes * 60_000L > cycleStarted.getTime();
        String balanceText = incident.getBalance() == null ? "供应商接口返回余额不足"
                : normalize(incident.getBalance()).toPlainString() + " " + StrUtil.blankToDefault(incident.getCurrency(), "CNY");
        String title = recovery ? "供应商余额恢复" : "供应商余额不足";
        String content = "供应商：" + provider.getProviderName() + "；当前余额：" + balanceText
                + "；状态：" + (recovery ? "已恢复" : severityName(incident.getSeverity()))
                + "；时间：" + DATE_TIME.format(LocalDateTime.now());
        for (ProviderBalanceRecipient recipient : recipients) {
            if (retryOnly && hasSuccessfulDeliverySince(incident.getId(), recipient.getId(),
                    incident.getLastNotifiedAt())) {
                continue;
            }
            DeliveryOutcome outcome;
            try {
                outcome = sendOne(recipient, provider, incident, recovery ? "RECOVERY" : "ALERT", title, content, balanceText);
            } catch (Exception ex) {
                outcome = new DeliveryOutcome(false, null, safeText(ex.getMessage(), 300));
            }
            recordDelivery(incident.getId(), provider.getId(), recipient,
                    recovery ? "RECOVERY" : "ALERT", outcome);
            if (outcome.success()) {
                successCount++;
            } else {
                failureCount++;
            }
        }
        if (!recovery) {
            if (!retryOnly && successCount > 0) {
                incident.setLastNotifiedAt(cycleStarted);
            }
            Date nextNotify;
            if (failureCount > 0 || recipients.isEmpty()) {
                nextNotify = new Date(System.currentTimeMillis()
                        + Math.max(1, getSettings().getFailureRetryMinutes()) * 60_000L);
            } else if (retryOnly && incident.getLastNotifiedAt() != null) {
                nextNotify = new Date(incident.getLastNotifiedAt().getTime() + repeatMinutes * 60_000L);
            } else {
                nextNotify = new Date(System.currentTimeMillis() + repeatMinutes * 60_000L);
            }
            incident.setNextNotifyAt(nextNotify);
            incident.setUpdateTime(new Date());
            incidentMapper.updateById(incident);
        }
    }

    private DeliveryOutcome sendOne(ProviderBalanceRecipient recipient, AidAiProvider provider,
                                    ProviderBalanceIncident incident, String type, String title,
                                    String content, String balanceText) {
        assertChannelEnabled(recipient.getChannel());
        if ("EMAIL".equals(recipient.getChannel())) {
            String html = "<div style=\"font-family:Arial,sans-serif;line-height:1.7\"><h2>"
                    + HtmlUtils.htmlEscape(title) + "</h2><p>" + HtmlUtils.htmlEscape(content)
                    + "</p><p style=\"color:#86909c\">本消息由供应商余额监控自动发送。</p></div>";
            String messageId = mailTemplateFactory.sendHtml(recipient.getTargetValue(), "[AID] " + title, html);
            return new DeliveryOutcome(true, messageId, null);
        }
        if ("SMS".equals(recipient.getChannel())) {
            ProviderBalanceSettings settings = getSettings();
            if (StrUtil.isBlank(settings.getSmsTemplateId())) {
                return new DeliveryOutcome(false, null, "余额提醒短信模板未配置");
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("provider", provider == null ? "供应商" : safeText(provider.getProviderName(), 20));
            params.put("balance", StrUtil.blankToDefault(balanceText, "测试"));
            params.put("status", incident == null ? "测试" : severityName(incident.getSeverity()));
            SmsResult result = smsTemplateFactory.send(recipient.getTargetValue(), settings.getSmsTemplateId(), params);
            return new DeliveryOutcome(result != null && result.isSuccess(), null,
                    result == null || result.isSuccess() ? null : result.getMessage());
        }
        if ("WECHAT".equals(recipient.getChannel())) {
            ProviderBalanceSettings settings = getSettings();
            if (StrUtil.isBlank(settings.getWechatTemplateId())) {
                return new DeliveryOutcome(false, null, "微信模板 ID 未配置");
            }
            WechatTemplatePayload payload = new WechatTemplatePayload();
            payload.setOpenid(recipient.getTargetValue());
            payload.setTemplateId(settings.getWechatTemplateId());
            payload.setUrl(settings.getWechatJumpUrl());
            payload.setClientMsgId("pbal_" + recipient.getId() + "_" + System.currentTimeMillis());
            payload.getData().put(settings.getWechatProviderField(), provider == null ? "余额监控测试" : provider.getProviderName());
            payload.getData().put(settings.getWechatBalanceField(), StrUtil.blankToDefault(balanceText, "测试"));
            payload.getData().put(settings.getWechatStatusField(), incident == null ? "通道测试" : severityName(incident.getSeverity()));
            payload.getData().put(settings.getWechatTimeField(), DATE_TIME.format(LocalDateTime.now()));
            WechatTemplateSendResult result = wechatSender.send(payload);
            return new DeliveryOutcome(result != null && result.success(),
                    result == null || result.getMsgid() == null ? null : String.valueOf(result.getMsgid()),
                    result == null || result.success() ? null : result.getErrmsg());
        }
        return new DeliveryOutcome(false, null, "不支持的通知渠道");
    }

    private void sendDailyReportIfDue(ProviderBalanceSettings settings) {
        if (!settings.isDailyReportEnabled() || !mailConfigManager.isEnabled()) {
            return;
        }
        LocalTime reportTime = parseReportTime(settings.getDailyReportTime());
        if (LocalTime.now().isBefore(reportTime)) {
            return;
        }
        List<ProviderBalanceRecipient> recipients = activeRecipients(null, true).stream()
                .filter(v -> "EMAIL".equals(v.getChannel())).toList();
        if (recipients.isEmpty()) {
            return;
        }
        List<ProviderBalanceConfig> configs = configMapper.selectList(Wrappers.<ProviderBalanceConfig>lambdaQuery()
                .eq(ProviderBalanceConfig::getEnabled, 1));
        Date dayStart = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        for (ProviderBalanceRecipient recipient : recipients) {
            ProviderBalanceDelivery latest = deliveryMapper.selectOne(Wrappers.<ProviderBalanceDelivery>lambdaQuery()
                    .eq(ProviderBalanceDelivery::getRecipientId, recipient.getId())
                    .eq(ProviderBalanceDelivery::getDeliveryType, "DAILY")
                    .ge(ProviderBalanceDelivery::getAttemptedAt, dayStart)
                    .orderByDesc(ProviderBalanceDelivery::getAttemptedAt)
                    .last("limit 1"));
            if (latest != null && ("SUCCESS".equals(latest.getStatus())
                    || latest.getAttemptedAt().getTime() + settings.getFailureRetryMinutes() * 60_000L
                    > System.currentTimeMillis())) {
                continue;
            }
            DeliveryOutcome outcome;
            try {
                Set<Long> subscribed = parseProviderIds(recipient.getProviderIds());
                String html = buildDailyReport(configs.stream()
                        .filter(v -> subscribed.contains(v.getProviderId())).toList());
                String messageId = mailTemplateFactory.sendHtml(recipient.getTargetValue(),
                        "[AID] 供应商余额日报 " + LocalDate.now(), html);
                outcome = new DeliveryOutcome(true, messageId, null);
            } catch (Exception ex) {
                outcome = new DeliveryOutcome(false, null, safeText(ex.getMessage(), 300));
            }
            recordDelivery(null, null, recipient, "DAILY", outcome);
        }
    }

    private String buildDailyReport(List<ProviderBalanceConfig> configs) {
        StringBuilder html = new StringBuilder("<div style=\"font-family:Arial,sans-serif;color:#1d2129\">")
                .append("<h2>供应商余额日报</h2><p>").append(LocalDate.now()).append("</p>")
                .append("<table style=\"border-collapse:collapse;width:100%\"><thead><tr>")
                .append("<th style=\"border:1px solid #e5e6eb;padding:10px;text-align:left\">供应商</th>")
                .append("<th style=\"border:1px solid #e5e6eb;padding:10px;text-align:left\">余额</th>")
                .append("<th style=\"border:1px solid #e5e6eb;padding:10px;text-align:left\">来源/状态</th></tr></thead><tbody>");
        for (ProviderBalanceConfig config : configs) {
            AidAiProvider provider = providerService.selectAidAiProviderById(config.getProviderId());
            String name = provider == null ? "供应商#" + config.getProviderId() : provider.getProviderName();
            String balance = config.getCurrentBalance() == null ? "不可查询/不可统计"
                    : normalize(config.getCurrentBalance()).toPlainString() + " " + StrUtil.blankToDefault(config.getCurrency(), "CNY");
            html.append("<tr><td style=\"border:1px solid #e5e6eb;padding:10px\">")
                    .append(HtmlUtils.htmlEscape(name)).append("</td><td style=\"border:1px solid #e5e6eb;padding:10px\">")
                    .append(HtmlUtils.htmlEscape(balance)).append("</td><td style=\"border:1px solid #e5e6eb;padding:10px\">")
                    .append(HtmlUtils.htmlEscape(StrUtil.blankToDefault(config.getCurrentSource(), "UNAVAILABLE")
                            + " / " + StrUtil.blankToDefault(config.getCurrentStatus(), "UNKNOWN"))).append("</td></tr>");
        }
        return html.append("</tbody></table><p style=\"color:#86909c\">查询失败只在日报中标记，不会触发短信或微信异常通知。</p></div>").toString();
    }

    private void cleanupHistoryIfDue(ProviderBalanceSettings settings) {
        LocalDate today = LocalDate.now();
        if (LocalTime.now().getHour() < 3 || Objects.equals(lastCleanupDate, today)) {
            return;
        }
        try {
            Date snapshotBefore = Date.from(today.minusDays(settings.getSnapshotRetentionDays())
                    .atStartOfDay(ZoneId.systemDefault()).toInstant());
            Date deliveryBefore = Date.from(today.minusDays(settings.getDeliveryRetentionDays())
                    .atStartOfDay(ZoneId.systemDefault()).toInstant());
            deleteOldSnapshots(snapshotBefore);
            deleteOldDeliveries(deliveryBefore);
            lastCleanupDate = today;
        } catch (Exception ex) {
            log.error("供应商余额监控历史清理失败", ex);
        }
    }

    private void deleteOldSnapshots(Date before) {
        for (int i = 0; i < 10; i++) {
            int deleted = snapshotMapper.delete(Wrappers.<ProviderBalanceSnapshot>lambdaQuery()
                    .lt(ProviderBalanceSnapshot::getCheckedAt, before).last("limit 1000"));
            if (deleted < 1000) return;
        }
    }

    private void deleteOldDeliveries(Date before) {
        for (int i = 0; i < 10; i++) {
            int deleted = deliveryMapper.delete(Wrappers.<ProviderBalanceDelivery>lambdaQuery()
                    .lt(ProviderBalanceDelivery::getAttemptedAt, before).last("limit 1000"));
            if (deleted < 1000) return;
        }
    }

    private void saveSnapshot(ProviderBalanceConfig config, BigDecimal balance, String source, String status,
                              String precision, Object detail, String error) {
        ProviderBalanceSnapshot snapshot = new ProviderBalanceSnapshot();
        snapshot.setProviderId(config.getProviderId());
        snapshot.setSourceType(source);
        snapshot.setBalance(balance);
        snapshot.setCurrency(config.getCurrency());
        snapshot.setStatus(status);
        snapshot.setPrecisionType(precision);
        snapshot.setDetailJson(detail == null ? null : safeText(JSON.toJSONString(detail), 2000));
        snapshot.setErrorMessage(safeText(error, 300));
        snapshot.setCheckedAt(new Date());
        snapshot.setCreateBy("system");
        snapshot.setCreateTime(new Date());
        snapshotMapper.insert(snapshot);
    }

    private BigDecimal calculateSimulatedBalance(ProviderBalanceConfig config) {
        BigDecimal result = value(config.getInitialAmount());
        List<ProviderCostLedger> entries = ledgerMapper.selectList(Wrappers.<ProviderCostLedger>lambdaQuery()
                .eq(ProviderCostLedger::getProviderId, config.getProviderId())
                .ge(config.getInitialTime() != null, ProviderCostLedger::getOccurredAt, config.getInitialTime()));
        for (ProviderCostLedger entry : entries) {
            if ("COST".equals(entry.getEntryType())) {
                BigDecimal multiplier = config.getCostUnitMultiplier() == null
                        ? BigDecimal.ONE : config.getCostUnitMultiplier();
                result = result.subtract(value(entry.getAmount()).multiply(multiplier));
            } else {
                result = result.add(value(entry.getBalanceDelta()));
            }
        }
        return normalize(result);
    }

    /** 每轮最多补齐 200 条，避免大库首次启用时形成长事务或阻塞调度。 */
    private void reconcileProviderCosts(ProviderBalanceConfig config) {
        if (config.getInitialTime() == null) return;
        List<AidMediaTask> tasks = mediaTaskMapper.selectUnledgeredProviderCosts(
                config.getProviderId(), config.getInitialTime(), 200);
        for (AidMediaTask task : tasks) {
            try { recordTaskCost(task.getId()); }
            catch (Exception ex) { log.error("历史供应商成本补齐失败: taskId={}", task.getId(), ex); }
        }
    }

    private BigDecimal calculateRunwayDays(ProviderBalanceConfig config) {
        if (!Objects.equals(config.getForecastEnabled(), 1) || config.getCurrentBalance() == null) {
            return null;
        }
        Date from = new Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000);
        BigDecimal officialCost = ledgerMapper.selectList(Wrappers.<ProviderCostLedger>lambdaQuery()
                        .eq(ProviderCostLedger::getProviderId, config.getProviderId())
                        .eq(ProviderCostLedger::getEntryType, "COST")
                        .ge(ProviderCostLedger::getOccurredAt, from)).stream()
                .map(ProviderCostLedger::getAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cost = officialCost.multiply(config.getCostUnitMultiplier() == null
                ? BigDecimal.ONE : config.getCostUnitMultiplier());
        BigDecimal daily = cost.divide(BigDecimal.valueOf(7), 8, RoundingMode.HALF_UP);
        return daily.compareTo(BigDecimal.ZERO) <= 0 ? null
                : config.getCurrentBalance().max(BigDecimal.ZERO).divide(daily, 1, RoundingMode.HALF_UP);
    }

    private BigDecimal extractBalance(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number number) return new BigDecimal(number.toString());
        if (raw instanceof Map<?, ?> map) {
            for (String key : List.of("remaining_quantity", "balance", "credits", "available", "available_balance")) {
                Object value = map.get(key);
                BigDecimal parsed = decimal(value);
                if (parsed != null) return parsed;
            }
            for (Object value : map.values()) {
                BigDecimal nested = extractBalance(value);
                if (nested != null) return nested;
            }
        }
        if (raw instanceof Iterable<?> iterable) {
            BigDecimal total = BigDecimal.ZERO;
            boolean found = false;
            for (Object value : iterable) {
                BigDecimal nested = extractBalance(value);
                if (nested != null) {
                    total = total.add(nested);
                    found = true;
                }
            }
            return found ? total : null;
        }
        return decimal(raw);
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return null;
        try {
            String text = value.toString().trim();
            return text.matches("-?\\d+(\\.\\d+)?") ? new BigDecimal(text) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<ProviderBalanceRecipient> activeRecipients(Long providerId, boolean daily) {
        List<ProviderBalanceRecipient> all = recipientMapper.selectList(Wrappers.<ProviderBalanceRecipient>lambdaQuery()
                .eq(ProviderBalanceRecipient::getEnabled, 1)
                .eq(daily, ProviderBalanceRecipient::getDailyReportEnabled, 1));
        if (providerId == null) return all;
        return all.stream().filter(v -> parseProviderIds(v.getProviderIds()).contains(providerId)).toList();
    }

    private ProviderBalanceIncident currentIncident(Long providerId) {
        return incidentMapper.selectOne(Wrappers.<ProviderBalanceIncident>lambdaQuery()
                .eq(ProviderBalanceIncident::getProviderId, providerId)
                .in(ProviderBalanceIncident::getStatus, "OPEN", "ACKED")
                .orderByDesc(ProviderBalanceIncident::getId).last("limit 1"));
    }

    private ProviderBalanceIncident currentIncidentForUpdate(Long providerId) {
        return incidentMapper.selectOne(Wrappers.<ProviderBalanceIncident>lambdaQuery()
                .eq(ProviderBalanceIncident::getProviderId, providerId)
                .in(ProviderBalanceIncident::getStatus, "OPEN", "ACKED")
                .orderByDesc(ProviderBalanceIncident::getId).last("limit 1 for update"));
    }

    private void clearIncidentNotificationState(Long incidentId) {
        incidentMapper.update(null, Wrappers.<ProviderBalanceIncident>lambdaUpdate()
                .eq(ProviderBalanceIncident::getId, incidentId)
                .set(ProviderBalanceIncident::getStatus, "OPEN")
                .set(ProviderBalanceIncident::getAcknowledgedAt, null)
                .set(ProviderBalanceIncident::getAcknowledgedBy, null)
                .set(ProviderBalanceIncident::getLastNotifiedAt, null)
                .set(ProviderBalanceIncident::getNextNotifyAt, null));
    }

    private boolean hasSuccessfulDeliverySince(Long incidentId, Long recipientId, Date since) {
        return deliveryMapper.selectCount(Wrappers.<ProviderBalanceDelivery>lambdaQuery()
                .eq(ProviderBalanceDelivery::getIncidentId, incidentId)
                .eq(ProviderBalanceDelivery::getRecipientId, recipientId)
                .eq(ProviderBalanceDelivery::getDeliveryType, "ALERT")
                .eq(ProviderBalanceDelivery::getStatus, "SUCCESS")
                .ge(ProviderBalanceDelivery::getAttemptedAt, since)) > 0;
    }

    private void closeIncidentForDisabledProvider(Long providerId, String username) {
        configMapper.update(null, Wrappers.<ProviderBalanceConfig>lambdaUpdate()
                .eq(ProviderBalanceConfig::getProviderId, providerId)
                .set(ProviderBalanceConfig::getSilenceUntil, null)
                .set(ProviderBalanceConfig::getCurrentStatus, STATUS_NORMAL)
                .set(ProviderBalanceConfig::getConsecutiveLow, 0));
        ProviderBalanceIncident incident = currentIncident(providerId);
        if (incident == null) {
            return;
        }
        Date now = new Date();
        incident.setStatus("RESOLVED");
        incident.setResolvedAt(now);
        incident.setReason(safeText(StrUtil.nullToEmpty(incident.getReason()) + "；监控已关闭", 300));
        incident.setUpdateBy(username);
        incident.setUpdateTime(now);
        incidentMapper.updateById(incident);
    }

    private ProviderBalanceConfig findConfig(Long providerId) {
        return configMapper.selectOne(Wrappers.<ProviderBalanceConfig>lambdaQuery()
                .eq(ProviderBalanceConfig::getProviderId, providerId).last("limit 1"));
    }

    private ProviderBalanceConfig findConfigForUpdate(Long providerId) {
        return configMapper.selectOne(Wrappers.<ProviderBalanceConfig>lambdaQuery()
                .eq(ProviderBalanceConfig::getProviderId, providerId).last("limit 1 for update"));
    }

    /** 显式写入可空字段，防止查询失败后沿用历史余额或历史错误摘要。 */
    private void persistCheckState(ProviderBalanceConfig config) {
        configMapper.update(null, Wrappers.<ProviderBalanceConfig>lambdaUpdate()
                .eq(ProviderBalanceConfig::getId, config.getId())
                .set(ProviderBalanceConfig::getCurrentBalance, config.getCurrentBalance())
                .set(ProviderBalanceConfig::getCurrentSource, config.getCurrentSource())
                .set(ProviderBalanceConfig::getSimulatedBalance, config.getSimulatedBalance())
                .set(ProviderBalanceConfig::getLastCheckTime, config.getLastCheckTime())
                .set(ProviderBalanceConfig::getLastSuccessTime, config.getLastSuccessTime())
                .set(ProviderBalanceConfig::getLastError, config.getLastError()));
    }

    private boolean isApiSupported(Long providerId) {
        try {
            return Boolean.TRUE.equals(upstreamOperationsService.capabilities(providerId).get("balance"));
        } catch (Exception ex) {
            return false;
        }
    }

    private Object upstreamBalanceUnit(Long providerId) {
        try { return upstreamOperationsService.capabilities(providerId).get("balanceUnit"); }
        catch (Exception ex) { return null; }
    }

    private void recordDelivery(Long incidentId, Long providerId, ProviderBalanceRecipient recipient,
                                String type, DeliveryOutcome outcome) {
        ProviderBalanceDelivery delivery = new ProviderBalanceDelivery();
        delivery.setIncidentId(incidentId);
        delivery.setProviderId(providerId);
        delivery.setRecipientId(recipient.getId());
        delivery.setChannel(recipient.getChannel());
        delivery.setDeliveryType(type);
        delivery.setStatus(outcome.success() ? "SUCCESS" : "FAILED");
        delivery.setMessageId(safeText(outcome.messageId(), 128));
        delivery.setErrorMessage(safeText(outcome.error(), 300));
        delivery.setAttemptedAt(new Date());
        delivery.setSucceededAt(outcome.success() ? new Date() : null);
        delivery.setCreateBy("system");
        delivery.setCreateTime(new Date());
        try {
            deliveryMapper.insert(delivery);
        } catch (Exception ex) {
            log.error("供应商余额通知结果落库失败: recipientId={}", recipient.getId(), ex);
        }
    }

    private Map<String, Object> recipientView(ProviderBalanceRecipient recipient) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", recipient.getId());
        result.put("recipientName", recipient.getRecipientName());
        result.put("channel", recipient.getChannel());
        result.put("displayValue", recipient.getDisplayValue());
        result.put("wechatNickname", recipient.getWechatNickname());
        result.put("enabled", Objects.equals(recipient.getEnabled(), 1));
        result.put("dailyReportEnabled", Objects.equals(recipient.getDailyReportEnabled(), 1));
        result.put("providerIds", parseProviderIds(recipient.getProviderIds()));
        result.put("createTime", recipient.getCreateTime());
        return result;
    }

    private void normalizeAndValidateProvider(ProviderBalanceConfig input, Long providerId) {
        input.setEnabled(Objects.equals(input.getEnabled(), 1) ? 1 : 0);
        input.setApiEnabled(Objects.equals(input.getApiEnabled(), 1) ? 1 : 0);
        input.setSimulatedEnabled(Objects.equals(input.getSimulatedEnabled(), 1) ? 1 : 0);
        input.setErrorRuleEnabled(Objects.equals(input.getErrorRuleEnabled(), 0) ? 0 : 1);
        input.setForecastEnabled(Objects.equals(input.getForecastEnabled(), 1) ? 1 : 0);
        input.setCurrency(StrUtil.blankToDefault(StrUtil.trim(input.getCurrency()), "CNY").toUpperCase(Locale.ROOT));
        if (!input.getCurrency().matches("[A-Z0-9_]{1,16}")) {
            throw new ServiceException("余额单位格式错误");
        }
        input.setCostUnitMultiplier(input.getCostUnitMultiplier() == null ? BigDecimal.ONE : input.getCostUnitMultiplier());
        if (input.getCostUnitMultiplier().compareTo(BigDecimal.ZERO) <= 0
                || input.getCostUnitMultiplier().compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
            throw new ServiceException("成本单位换算倍率必须大于 0 且不超过 1000000");
        }
        input.setRepeatIntervalMinutes(clamp(input.getRepeatIntervalMinutes(), 5, 10080, 360));
        input.setQueryIntervalMinutes(clamp(input.getQueryIntervalMinutes(), 1, 1440, 10));
        input.setConfirmCount(clamp(input.getConfirmCount(), 1, 10, 2));
        input.setForecastDays(clamp(input.getForecastDays(), 1, 90, 7));
        if (Objects.equals(input.getEnabled(), 1)
                && !Objects.equals(input.getApiEnabled(), 1)
                && !Objects.equals(input.getSimulatedEnabled(), 1)
                && !Objects.equals(input.getErrorRuleEnabled(), 1)) {
            throw new ServiceException("至少开启一种余额检测方式");
        }
        if (Objects.equals(input.getApiEnabled(), 1) && !isApiSupported(providerId)) {
            throw new ServiceException("该供应商暂不支持官方余额接口，请使用模拟余额或错误规则检测");
        }
        if (Objects.equals(input.getSimulatedEnabled(), 1)
                && (input.getInitialAmount() == null || input.getInitialTime() == null)) {
            throw new ServiceException("模拟余额必须填写初始金额和生效时间");
        }
        validateNonNegativeAmount(input.getInitialAmount(), "初始余额超出范围");
        if (Objects.equals(input.getForecastEnabled(), 1) && input.getInitialTime() == null) {
            throw new ServiceException("余额预测必须填写成本统计开始时间");
        }
        if (input.getCriticalThreshold() == null || input.getWarningThreshold() == null) {
            throw new ServiceException("请填写预警阈值和严重阈值");
        }
        validateNonNegativeAmount(input.getCriticalThreshold(), "严重阈值超出范围");
        validateNonNegativeAmount(input.getWarningThreshold(), "预警阈值超出范围");
        if (input.getCriticalThreshold().compareTo(input.getWarningThreshold()) > 0) {
            throw new ServiceException("严重阈值不能高于预警阈值");
        }
        if (input.getRecoveryThreshold() == null) {
            input.setRecoveryThreshold(input.getWarningThreshold());
        }
        validateNonNegativeAmount(input.getRecoveryThreshold(), "恢复阈值超出范围");
        if (input.getRecoveryThreshold().compareTo(input.getWarningThreshold()) < 0) {
            throw new ServiceException("恢复阈值不能低于预警阈值");
        }
    }

    private void validateSettings(ProviderBalanceSettings settings) {
        settings.setDailyReportTime(parseReportTime(settings.getDailyReportTime())
                .format(DateTimeFormatter.ofPattern("HH:mm")));
        settings.setDefaultRepeatIntervalMinutes(clamp(settings.getDefaultRepeatIntervalMinutes(), 5, 10080, 360));
        settings.setFailureRetryMinutes(clamp(settings.getFailureRetryMinutes(), 1, 1440, 10));
        settings.setSnapshotRetentionDays(clamp(settings.getSnapshotRetentionDays(), 7, 3650, 90));
        settings.setDeliveryRetentionDays(clamp(settings.getDeliveryRetentionDays(), 7, 3650, 180));
        settings.setSmsTemplateId(safeText(settings.getSmsTemplateId(), 500));
        settings.setWechatTemplateId(safeText(settings.getWechatTemplateId(), 128));
        settings.setWechatJumpUrl(safeText(settings.getWechatJumpUrl(), 500));
        settings.setWechatProviderField(validWechatField(settings.getWechatProviderField(), "thing1"));
        settings.setWechatBalanceField(validWechatField(settings.getWechatBalanceField(), "amount2"));
        settings.setWechatStatusField(validWechatField(settings.getWechatStatusField(), "thing3"));
        settings.setWechatTimeField(validWechatField(settings.getWechatTimeField(), "time4"));
        if (settings.isEnabled() && settings.isDailyReportEnabled() && !mailConfigManager.isEnabled()) {
            throw new ServiceException("请先启用邮箱服务");
        }
        if (settings.isEnabled()) {
            List<ProviderBalanceConfig> providers = configMapper.selectList(Wrappers.<ProviderBalanceConfig>lambdaQuery()
                    .eq(ProviderBalanceConfig::getEnabled, 1));
            List<ProviderBalanceRecipient> recipients = recipientMapper.selectList(
                    Wrappers.<ProviderBalanceRecipient>lambdaQuery().eq(ProviderBalanceRecipient::getEnabled, 1));
            if (providers.isEmpty()) throw new ServiceException("开启模块前请至少选择一个供应商");
            if (recipients.isEmpty()) throw new ServiceException("开启模块前请至少添加一个提醒人");
            Set<Long> coveredProviders = new LinkedHashSet<>();
            for (ProviderBalanceRecipient recipient : recipients) {
                assertChannelEnabled(recipient.getChannel());
                if ("SMS".equals(recipient.getChannel()) && StrUtil.isBlank(settings.getSmsTemplateId())) {
                    throw new ServiceException("短信提醒人已启用，请配置余额短信模板");
                }
                if ("WECHAT".equals(recipient.getChannel()) && StrUtil.isBlank(settings.getWechatTemplateId())) {
                    throw new ServiceException("微信提醒人已启用，请配置微信模板 ID");
                }
                coveredProviders.addAll(parseProviderIds(recipient.getProviderIds()));
            }
            if (providers.stream().map(ProviderBalanceConfig::getProviderId)
                    .anyMatch(providerId -> !coveredProviders.contains(providerId))) {
                throw new ServiceException("每个监控供应商至少配置一个提醒人");
            }
            if (settings.isDailyReportEnabled()) {
                if (!mailConfigManager.isEnabled()) throw new ServiceException("开启余额日报前请先启用邮箱服务");
                List<ProviderBalanceRecipient> reportRecipients = recipients.stream()
                        .filter(v -> "EMAIL".equals(v.getChannel())
                                && Objects.equals(v.getDailyReportEnabled(), 1)).toList();
                if (reportRecipients.isEmpty()) throw new ServiceException("开启余额日报前请至少设置一个日报邮箱");
                Set<Long> reportCovered = reportRecipients.stream()
                        .flatMap(v -> parseProviderIds(v.getProviderIds()).stream())
                        .collect(Collectors.toSet());
                if (providers.stream().map(ProviderBalanceConfig::getProviderId)
                        .anyMatch(providerId -> !reportCovered.contains(providerId))) {
                    throw new ServiceException("日报邮箱需覆盖全部监控供应商");
                }
            }
        }
    }

    private void validateRunningConfiguration() {
        ProviderBalanceSettings settings = getSettings();
        if (settings.isEnabled()) {
            validateSettings(settings);
        }
    }

    private String validWechatField(String value, String fallback) {
        String field = StrUtil.blankToDefault(StrUtil.trim(value), fallback);
        if (!field.matches("[A-Za-z_][A-Za-z0-9_]{0,31}")) {
            throw new ServiceException("微信模板字段名格式不正确");
        }
        return field;
    }

    private LocalTime parseReportTime(String value) {
        try {
            return LocalTime.parse(StrUtil.blankToDefault(value, "09:00"), DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException ex) {
            throw new ServiceException("日报时间格式应为 HH:mm");
        }
    }

    private void assertChannelEnabled(String channel) {
        boolean enabled = switch (StrUtil.nullToEmpty(channel).toUpperCase(Locale.ROOT)) {
            case "EMAIL" -> mailConfigManager.isEnabled();
            case "SMS" -> smsConfigManager.isEnabled();
            case "WECHAT" -> wxLoginTemplateFactory.isEnabled();
            default -> false;
        };
        if (!enabled) {
            throw new ServiceException("所选通知渠道尚未在系统配置中启用");
        }
    }

    private void validateTarget(String channel, String target) {
        if (StrUtil.isBlank(target)) {
            throw new ServiceException("接收地址不能为空");
        }
        if ("EMAIL".equals(channel) && !target.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ServiceException("邮箱格式不正确");
        }
        if (target.length() > 255) {
            throw new ServiceException("接收地址过长");
        }
        if ("SMS".equals(channel) && !target.matches("^\\+?[0-9]{6,20}$")) {
            throw new ServiceException("手机号格式不正确");
        }
    }

    public void validateProviderIds(String raw) {
        Set<Long> ids = parseProviderIds(raw);
        if (ids.isEmpty()) {
            throw new ServiceException("至少选择一个供应商");
        }
        long enabled = configMapper.selectCount(Wrappers.<ProviderBalanceConfig>lambdaQuery()
                .in(ProviderBalanceConfig::getProviderId, ids)
                .eq(ProviderBalanceConfig::getEnabled, 1));
        if (enabled != ids.size()) {
            throw new ServiceException("提醒人只能关联已选择监控的供应商");
        }
    }

    public String normalizeProviderIds(Object ids) {
        if (ids == null) return "";
        Set<Long> values = new LinkedHashSet<>();
        if (ids instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                try { values.add(Long.valueOf(String.valueOf(item))); } catch (Exception ignored) { }
            }
        } else {
            values.addAll(parseProviderIds(String.valueOf(ids)));
        }
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private Set<Long> parseProviderIds(String raw) {
        Set<Long> result = new LinkedHashSet<>();
        for (String part : StrUtil.nullToEmpty(raw).split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) result.add(id);
            } catch (Exception ignored) { }
        }
        return result;
    }

    private Map<String, Object> defaultProviderValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", false);
        values.put("apiEnabled", false);
        values.put("simulatedEnabled", false);
        values.put("errorRuleEnabled", true);
        values.put("forecastEnabled", false);
        values.put("currency", "CNY");
        values.put("costUnitMultiplier", BigDecimal.ONE);
        values.put("warningThreshold", BigDecimal.valueOf(100));
        values.put("criticalThreshold", BigDecimal.valueOf(20));
        values.put("recoveryThreshold", BigDecimal.valueOf(120));
        values.put("forecastDays", 7);
        values.put("repeatIntervalMinutes", 360);
        values.put("queryIntervalMinutes", 10);
        values.put("confirmCount", 2);
        values.put("currentStatus", STATUS_NORMAL);
        return values;
    }

    private boolean isCheckDue(ProviderBalanceConfig config) {
        int minutes = Math.max(1, Objects.requireNonNullElse(config.getQueryIntervalMinutes(), 10));
        return config.getLastCheckTime() == null
                || config.getLastCheckTime().getTime() + minutes * 60_000L <= System.currentTimeMillis();
    }

    private boolean isSilenced(ProviderBalanceConfig config) {
        return config.getSilenceUntil() != null && config.getSilenceUntil().after(new Date());
    }

    private String severityName(String severity) {
        return STATUS_CRITICAL.equals(severity) ? "严重不足" : STATUS_WARNING.equals(severity) ? "余额预警" : "已恢复";
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        return value == null ? fallback : Math.max(min, Math.min(max, value));
    }

    private void validateNonNegativeAmount(BigDecimal amount, String message) {
        if (amount != null && (amount.compareTo(BigDecimal.ZERO) < 0
                || amount.compareTo(MAX_AMOUNT) > 0)) {
            throw new ServiceException(message);
        }
    }

    private BigDecimal value(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private BigDecimal normalize(BigDecimal amount) {
        return amount == null ? null : amount.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private String safeText(String value, int max) {
        String text = StrUtil.trimToEmpty(value).replace('\r', ' ').replace('\n', ' ');
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String maskTarget(String channel, String target) {
        if ("EMAIL".equals(channel)) {
            int at = target.indexOf('@');
            return at > 0 ? target.substring(0, 1) + "***" + target.substring(at) : "***";
        }
        if ("SMS".equals(channel) && target.length() >= 7) {
            return target.substring(0, 3) + "****" + target.substring(target.length() - 4);
        }
        return target.length() <= 8 ? "****" : target.substring(0, 4) + "****" + target.substring(target.length() - 4);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record DeliveryOutcome(boolean success, String messageId, String error) { }
}
