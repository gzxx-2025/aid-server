package com.aid.notify.wechat.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidUserProfile;
import com.aid.aid.domain.AidUserSocial;
import com.aid.aid.domain.AidWechatNotifyLog;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.aid.service.IAidUserProfileService;
import com.aid.aid.service.IAidUserSocialService;
import com.aid.aid.service.IAidWechatNotifyLogService;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.core.service.ISysUserService;
import com.aid.notify.wechat.config.WechatNotifyConfig;
import com.aid.notify.wechat.config.WechatNotifyTemplateConfig;
import com.aid.notify.wechat.service.IWechatNotifyConfigService;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.notify.wechat.service.IWechatTemplateMessageSender;
import com.aid.notify.wechat.vo.WechatNotifyPreferenceVO;
import com.aid.notify.wechat.vo.WechatTemplatePayload;
import com.aid.notify.wechat.vo.WechatTemplateSendResult;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 微信模板消息推送门面实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatNotifyServiceImpl implements IWechatNotifyService
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String PLATFORM_WECHAT = "wechat";
    private static final String LOG_SUCCESS = "SUCCESS";
    private static final String LOG_FAILED = "FAILED";
    private static final String LOG_SKIPPED = "SKIPPED";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    private static final int WECHAT_INVALID_OPENID = 40003;
    /** 微信模板 thing / amount 字段官方上限 20 字，超长会发送失败，拼装文案时必须主动控制 */
    private static final int WECHAT_FIELD_MAX_LEN = 20;
    private static final String BALANCE_NOTIFY_LOCK_PREFIX = "wechat:notify:balance:";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private static final Set<String> PUSHABLE_TASK_TYPES = Set.of(
            "asset_extract",
            "form_generate_batch",
            "form_image_batch",
            "form_card_image_batch",
            "storyboard_script_batch",
            "storyboard_image_prompt_batch",
            "storyboard_video_prompt_batch",
            "storyboard_image_generate",
            "storyboard_video_generate"
    );

    private final IWechatNotifyConfigService configService;
    private final IWechatTemplateMessageSender messageSender;
    private final IAidWechatNotifyLogService notifyLogService;
    private final IAidExtractTaskService extractTaskService;
    private final IAidUserProfileService userProfileService;
    private final IAidUserSocialService userSocialService;
    private final IAidStoryboardService storyboardService;
    private final IAidComicProjectService comicProjectService;
    private final IAidComicEpisodeService comicEpisodeService;
    private final IAidRolePropSceneService rolePropSceneService;
    private final IAidRolePropSceneFormService rolePropSceneFormService;
    private final IAidRolePropSceneFormImageService rolePropSceneFormImageService;
    private final ISysUserService sysUserService;
    private final RedisCache redisCache;
    private final BillingPriceMultiplierService billingPriceMultiplierService;

    @Override
    public void notifyTaskStarted(Long taskId)
    {
        try
        {
            AidExtractTask task = loadTask(taskId);
            if (Objects.isNull(task) || !isPushableFullBatch(task))
            {
                return;
            }
            Map<String, String> data = new LinkedHashMap<>();
            data.put("serviceProject", taskDisplayName(task));
            data.put("startTime", nowText());
            sendTaskTemplate(task, WechatNotifyConfigServiceImpl.EVENT_BATCH_STARTED, startRunStatus(task), data);
        }
        catch (Exception e)
        {
            log.warn("微信开始推送被跳过: taskId={}, err={}", taskId, e.getMessage());
        }
    }

    @Override
    public void notifyTaskTerminal(Long taskId)
    {
        try
        {
            AidExtractTask task = loadTask(taskId);
            if (Objects.isNull(task) || !isPushableFullBatch(task))
            {
                return;
            }
            String status = effectiveTerminalStatus(task);
            if (STATUS_SUCCEEDED.equals(status))
            {
                Map<String, String> data = new LinkedHashMap<>();
                data.put("projectName", taskDisplayName(task));
                data.put("finishTime", nowText());
                sendTaskTemplate(task, WechatNotifyConfigServiceImpl.EVENT_BATCH_SUCCEEDED,
                        terminalRunStatus(task, status), data);
                return;
            }
            if (STATUS_FAILED.equals(status) || STATUS_PARTIAL_FAILED.equals(status))
            {
                Map<String, String> data = new LinkedHashMap<>();
                data.put("productName", taskDisplayName(task));
                data.put("orderAmount", amountDataText(task.getActualCost(), task.getFrozenAmount()));
                data.put("failureTime", nowText());
                sendTaskTemplate(task, WechatNotifyConfigServiceImpl.EVENT_BATCH_FAILED,
                        terminalRunStatus(task, status), data);
            }
        }
        catch (Exception e)
        {
            log.warn("微信终态推送被跳过: taskId={}, err={}", taskId, e.getMessage());
        }
    }

    @Override
    public void notifyBalanceInsufficient(Long userId, String bizType, Long bizId, BigDecimal requiredAmount)
    {
        try
        {
            WechatNotifyConfig config = configService.getConfig();
            if (!Boolean.TRUE.equals(config.getEnabled()) || Objects.isNull(userId))
            {
                return;
            }
            String lockKey = BALANCE_NOTIFY_LOCK_PREFIX + userId;
            String lockToken = UUID.randomUUID().toString();
            if (!tryLockBalanceNotify(lockKey, lockToken))
            {
                return;
            }
            try
            {
                AidUserProfile profile = userProfileService.getByUserId(userId);
                if (!hasBalanceReminderAvailable(profile)
                        || !isBalanceReminderTrigger(profile, config, requiredAmount))
                {
                    return;
                }

                Map<String, String> data = new LinkedHashMap<>();
                data.put("accountName", "AID账户");
                data.put("currentBalance", balanceAmountText(userId));
                data.put("alarmTime", nowText());
                sendTemplate(userId, WechatNotifyConfigServiceImpl.EVENT_BALANCE_INSUFFICIENT,
                        StrUtil.blankToDefault(bizType, "balance"),
                        bizId == null ? userId : bizId,
                        null,
                        balanceRunStatus(),
                        data,
                        true);
            }
            finally
            {
                unlockBalanceNotify(lockKey, lockToken);
            }
        }
        catch (Exception e)
        {
            log.warn("微信余额不足推送被跳过: userId={}, bizType={}, bizId={}, err={}",
                    userId, bizType, bizId, e.getMessage());
        }
    }

    @Override
    public void notifyContentAudit(String targetType, Long targetId, String auditEvent, String reason)
    {
        try
        {
            if (Objects.isNull(targetId) || StrUtil.isBlank(auditEvent))
            {
                return;
            }
            // 解析内容归属用户与展示名（项目名 / 项目名·第N集）
            AuditNotifyTarget target = resolveAuditTarget(targetType, targetId);
            if (Objects.isNull(target) || Objects.isNull(target.userId()))
            {
                return;
            }
            String bizType = targetType + "_audit";
            String auditResult;
            switch (auditEvent)
            {
                case AUDIT_EVENT_SUBMITTED -> auditResult = "提交审核";
                case AUDIT_EVENT_PASSED -> auditResult = "审核通过";
                case AUDIT_EVENT_REJECTED -> {
                    log.info("审核驳回推送: targetType={}, targetId={}, reason={}", targetType, targetId, reason);
                    auditResult = "审核驳回";
                }
                case AUDIT_EVENT_PUBLISHED -> auditResult = "发布成功";
                case AUDIT_EVENT_REVOKED -> {
                    log.info("审核回撤推送: targetType={}, targetId={}, reason={}", targetType, targetId, reason);
                    auditResult = "审核回撤";
                }
                default -> {
                    log.info("未知审核推送事件被忽略: targetType={}, targetId={}, event={}",
                            targetType, targetId, auditEvent);
                    return;
                }
            }
            Map<String, String> data = new LinkedHashMap<>();
            data.put("projectName", cutByCodePoint(target.displayName(), WECHAT_FIELD_MAX_LEN));
            data.put("finishTime", nowText());
            data.put("auditResult", auditResult);
            sendTemplate(target.userId(), WechatNotifyConfigServiceImpl.EVENT_AUDIT_RESULT,
                    bizType, targetId, null, auditRunStatus(auditEvent), data, true);
        }
        catch (Exception e)
        {
            log.warn("微信审核状态推送被跳过: targetType={}, targetId={}, event={}, err={}",
                    targetType, targetId, auditEvent, e.getMessage());
        }
    }

    @Override
    public void notifyOrderRefund(Long userId, Long orderId, String orderName, String orderNo,
                                  String refundReason, BigDecimal refundAmount)
    {
        try
        {
            if (Objects.isNull(userId) || Objects.isNull(orderId) || StrUtil.isBlank(orderNo))
            {
                return;
            }
            Map<String, String> data = new LinkedHashMap<>();
            data.put("orderName", StrUtil.blankToDefault(orderName, "积分充值订单"));
            data.put("orderNo", orderNo);
            data.put("refundReason", StrUtil.blankToDefault(refundReason, "后台运营退款"));
            data.put("refundAmount", yuanAmountText(refundAmount));
            data.put("refundUser", resolveRefundUser(userId));
            sendTemplate(userId, WechatNotifyConfigServiceImpl.EVENT_ORDER_REFUND,
                    "pay_order_refund", orderId, null, "REFUNDED", data, true);
        }
        catch (Exception e)
        {
            log.warn("微信退款通知被跳过: userId={}, orderId={}, orderNo={}, err={}",
                    userId, orderId, orderNo, e.getMessage());
        }
    }

    @Override
    public WechatNotifyPreferenceVO getPreference(Long userId)
    {
        return buildPreference(userId);
    }

    @Override
    public WechatNotifyPreferenceVO enable(Long userId)
    {
        AidUserProfile profile = userProfileService.getByUserId(userId);
        if (Objects.isNull(profile))
        {
            log.info("微信推送开启失败，用户资料不存在: userId={}", userId);
            throw new ServiceException("用户不存在");
        }
        if (StrUtil.isBlank(resolveOpenid(userId)))
        {
            log.info("微信推送开启失败，未绑定微信: userId={}", userId);
            throw new ServiceException("未绑定微信");
        }
        updateUserNotifyEnabled(userId, true);
        return buildPreference(userId);
    }

    @Override
    public WechatNotifyPreferenceVO disable(Long userId)
    {
        updateUserNotifyEnabled(userId, false);
        return buildPreference(userId);
    }

    @Override
    public WechatTemplateSendResult testSend(String openid, String eventType)
    {
        WechatNotifyConfig config = configService.getConfig();
        String resolvedType = StrUtil.blankToDefault(eventType, WechatNotifyConfigServiceImpl.EVENT_BATCH_STARTED);
        WechatNotifyTemplateConfig template = config.getTemplates().get(resolvedType);
        if (Objects.isNull(template) || StrUtil.isBlank(template.getTemplateId()))
        {
            throw new ServiceException("模板未配置");
        }
        Map<String, String> sample = sampleData(resolvedType);
        WechatTemplatePayload payload = buildPayload(openid, template, sample,
                "aid_test_" + resolvedType + "_" + System.currentTimeMillis(), config.getJumpUrlBase());
        return messageSender.send(payload);
    }

    private void sendTaskTemplate(AidExtractTask task, String eventType, String bizStatus, Map<String, String> data)
    {
        sendTemplate(task.getUserId(), eventType, task.getTaskType(), task.getId(), task.getId(), bizStatus, data, true);
    }

    /** 审核推送目标：归属用户 + 展示名（项目名 / 项目名·第N集） */
    private record AuditNotifyTarget(Long userId, String displayName) {
    }

    /**
     * 解析审核对象的归属用户与展示名。
     * project → 项目名；episode → 项目名·第N集。查不到返回 null（跳过推送）。
     */
    private AuditNotifyTarget resolveAuditTarget(String targetType, Long targetId)
    {
        if ("project".equals(targetType))
        {
            // 查询字段精简：仅需归属与项目名（新增使用字段时此处必须同步补充）
            AidComicProject project = comicProjectService.getOne(Wrappers.<AidComicProject>lambdaQuery()
                    .select(AidComicProject::getId, AidComicProject::getUserId, AidComicProject::getProjectName)
                    .eq(AidComicProject::getId, targetId)
                    .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL)
                    .last("limit 1"), false);
            if (Objects.isNull(project))
            {
                return null;
            }
            return new AuditNotifyTarget(project.getUserId(), StrUtil.trimToEmpty(project.getProjectName()));
        }
        if ("episode".equals(targetType))
        {
            // 查询字段精简：仅需归属/项目/集号（新增使用字段时此处必须同步补充）
            AidComicEpisode episode = comicEpisodeService.getOne(Wrappers.<AidComicEpisode>lambdaQuery()
                    .select(AidComicEpisode::getId, AidComicEpisode::getUserId,
                            AidComicEpisode::getProjectId, AidComicEpisode::getEpisodeNo)
                    .eq(AidComicEpisode::getId, targetId)
                    .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL)
                    .last("limit 1"), false);
            if (Objects.isNull(episode))
            {
                return null;
            }
            String projectName = resolveProjectName(episode.getProjectId());
            String episodeLabel = episode.getEpisodeNo() == null ? "" : "第" + episode.getEpisodeNo() + "集";
            String displayName = StrUtil.isBlank(projectName) ? episodeLabel
                    : (StrUtil.isBlank(episodeLabel) ? projectName : projectName + "·" + episodeLabel);
            return new AuditNotifyTarget(episode.getUserId(), displayName);
        }
        return null;
    }

    /** 审核推送防重状态段：同一对象每次动作独立推送（提审可反复发生，不做跨次去重） */
    private String auditRunStatus(String auditEvent)
    {
        return "AUDIT_" + auditEvent.toUpperCase() + "_" + System.currentTimeMillis();
    }

    private void sendTemplate(Long userId, String eventType, String bizType, Long bizId, Long taskId,
                              String bizStatus, Map<String, String> data, boolean disableOnInvalidOpenid)
    {
        try
        {
            WechatNotifyConfig config = configService.getConfig();
            if (!Boolean.TRUE.equals(config.getEnabled()))
            {
                return;
            }
            AidUserProfile profile = userProfileService.getByUserId(userId);
            if (Objects.isNull(profile) || !Integer.valueOf(1).equals(profile.getWechatNotifyEnabled()))
            {
                return;
            }
            String openid = resolveOpenid(userId);
            if (StrUtil.isBlank(openid))
            {
                return;
            }
            if (!Boolean.TRUE.equals(configService.getStatus().getReady()))
            {
                saveSkipped(userId, openid, eventType, bizType, bizId, taskId, "配置未就绪");
                return;
            }
            WechatNotifyTemplateConfig template = config.getTemplates().get(eventType);
            if (Objects.isNull(template) || !Boolean.TRUE.equals(template.getEnabled())
                    || StrUtil.isBlank(template.getTemplateId()))
            {
                saveSkipped(userId, openid, eventType, bizType, bizId, taskId, "模板未配置");
                return;
            }
            if (isRateLimited(userId, config))
            {
                saveSkipped(userId, openid, eventType, bizType, bizId, taskId, "触达频率受限");
                return;
            }
            String clientMsgId = clientMsgId(eventType, userId, bizId, bizStatus);
            if (alreadySent(clientMsgId))
            {
                return;
            }
            WechatTemplatePayload payload = buildPayload(openid, template, data, clientMsgId,
                    buildJumpUrl(config.getJumpUrlBase(), taskId, bizType));
            WechatTemplateSendResult result = messageSender.send(payload);
            saveSendLog(userId, openid, eventType, bizType, bizId, taskId, template.getTemplateId(),
                    clientMsgId, payload, result);
            if (result.success() && WechatNotifyConfigServiceImpl.EVENT_BALANCE_INSUFFICIENT.equals(eventType))
            {
                consumeBalanceReminder(userId);
            }
            if (disableOnInvalidOpenid && Integer.valueOf(WECHAT_INVALID_OPENID).equals(result.getErrcode()))
            {
                log.info("微信模板消息OpenID不可用，关闭用户推送: userId={}, bizType={}, bizId={}",
                        userId, bizType, bizId);
                updateUserNotifyEnabled(userId, false);
            }
        }
        catch (Exception e)
        {
            log.error("微信模板消息推送失败: userId={}, eventType={}, bizType={}, bizId={}",
                    userId, eventType, bizType, bizId, e);
        }
    }

    private WechatTemplatePayload buildPayload(String openid, WechatNotifyTemplateConfig template,
                                               Map<String, String> values, String clientMsgId, String url)
    {
        WechatTemplatePayload payload = new WechatTemplatePayload();
        payload.setOpenid(openid);
        payload.setTemplateId(template.getTemplateId());
        payload.setUrl(url);
        payload.setClientMsgId(clientMsgId);
        Map<String, String> data = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : template.getFields().entrySet())
        {
            String businessField = entry.getKey();
            String keyword = entry.getValue();
            if (StrUtil.isBlank(keyword))
            {
                continue;
            }
            data.put(keyword, StrUtil.blankToDefault(values.get(businessField), "-"));
        }
        payload.setData(data);
        return payload;
    }

    private boolean isRateLimited(Long userId, WechatNotifyConfig config)
    {
        Date minuteSince = new Date(System.currentTimeMillis() - 60_000L);
        long minuteCount = notifyLogService.count(Wrappers.<AidWechatNotifyLog>lambdaQuery()
                .eq(AidWechatNotifyLog::getUserId, userId)
                .eq(AidWechatNotifyLog::getDelFlag, DEL_FLAG_NORMAL)
                .ge(AidWechatNotifyLog::getSendTime, minuteSince)
                .in(AidWechatNotifyLog::getStatus, LOG_SUCCESS, LOG_FAILED));
        if (minuteCount >= Math.max(1, config.getMinuteUserLimit()))
        {
            return true;
        }
        Date dayStart = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        long dayCount = notifyLogService.count(Wrappers.<AidWechatNotifyLog>lambdaQuery()
                .eq(AidWechatNotifyLog::getUserId, userId)
                .eq(AidWechatNotifyLog::getDelFlag, DEL_FLAG_NORMAL)
                .ge(AidWechatNotifyLog::getSendTime, dayStart)
                .in(AidWechatNotifyLog::getStatus, LOG_SUCCESS, LOG_FAILED));
        return dayCount >= Math.max(1, config.getDailyUserLimit());
    }

    private boolean alreadySent(String clientMsgId)
    {
        return notifyLogService.count(Wrappers.<AidWechatNotifyLog>lambdaQuery()
                .eq(AidWechatNotifyLog::getClientMsgId, clientMsgId)
                .eq(AidWechatNotifyLog::getStatus, LOG_SUCCESS)
                .eq(AidWechatNotifyLog::getDelFlag, DEL_FLAG_NORMAL)) > 0;
    }

    private void saveSendLog(Long userId, String openid, String eventType, String bizType, Long bizId, Long taskId,
                             String templateId, String clientMsgId, WechatTemplatePayload payload,
                             WechatTemplateSendResult result)
    {
        AidWechatNotifyLog logEntity = baseLog(userId, openid, eventType, bizType, bizId, taskId);
        logEntity.setStatus(result.success() ? LOG_SUCCESS : LOG_FAILED);
        logEntity.setTemplateId(templateId);
        logEntity.setClientMsgId(clientMsgId);
        logEntity.setErrcode(result.getErrcode());
        logEntity.setErrmsg(StrUtil.sub(StrUtil.nullToEmpty(result.getErrmsg()), 0, 500));
        logEntity.setResponseJson(result.getRawResponse());
        try
        {
            logEntity.setRequestJson(OBJECT_MAPPER.writeValueAsString(payload));
        }
        catch (Exception e)
        {
            logEntity.setRequestJson(null);
        }
        notifyLogService.save(logEntity);
    }

    private void saveSkipped(Long userId, String openid, String eventType, String bizType, Long bizId,
                             Long taskId, String reason)
    {
        AidWechatNotifyLog logEntity = baseLog(userId, openid, eventType, bizType, bizId, taskId);
        logEntity.setStatus(LOG_SKIPPED);
        logEntity.setErrmsg(reason);
        notifyLogService.save(logEntity);
    }

    private AidWechatNotifyLog baseLog(Long userId, String openid, String eventType, String bizType, Long bizId,
                                       Long taskId)
    {
        AidWechatNotifyLog logEntity = new AidWechatNotifyLog();
        logEntity.setUserId(userId);
        logEntity.setOpenid(openid);
        logEntity.setEventType(eventType);
        logEntity.setBizType(bizType);
        logEntity.setBizId(bizId);
        logEntity.setTaskId(taskId);
        logEntity.setSendTime(DateUtils.getNowDate());
        logEntity.setCreateTime(DateUtils.getNowDate());
        logEntity.setCreateBy("system");
        logEntity.setDelFlag(DEL_FLAG_NORMAL);
        return logEntity;
    }

    private WechatNotifyPreferenceVO buildPreference(Long userId)
    {
        WechatNotifyConfig config = configService.getConfig();
        AidUserProfile profile = userProfileService.getByUserId(userId);
        return WechatNotifyPreferenceVO.builder()
                .systemEnabled(Boolean.TRUE.equals(config.getEnabled()))
                .userEnabled(Objects.nonNull(profile) && Integer.valueOf(1).equals(profile.getWechatNotifyEnabled()))
                .wechatBound(StrUtil.isNotBlank(resolveOpenid(userId)))
                .balanceReminderThreshold(config.getBalanceReminderThreshold())
                .rules(configService.getRules())
                .build();
    }

    private void updateUserNotifyEnabled(Long userId, boolean enabled)
    {
        userProfileService.update(Wrappers.<AidUserProfile>lambdaUpdate()
                .eq(AidUserProfile::getUserId, userId)
                .eq(AidUserProfile::getDelFlag, DEL_FLAG_NORMAL)
                .set(AidUserProfile::getWechatNotifyEnabled, enabled ? 1 : 0)
                .set(AidUserProfile::getUpdateTime, DateUtils.getNowDate())
                .set(AidUserProfile::getUpdateBy, String.valueOf(userId)));
    }

    private boolean hasBalanceReminderAvailable(AidUserProfile profile)
    {
        return Objects.nonNull(profile) && Integer.valueOf(1).equals(profile.getBalanceReminderAvailable());
    }

    private boolean tryLockBalanceNotify(String lockKey, String lockToken)
    {
        Boolean locked = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockToken, 60, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(locked);
    }

    private void unlockBalanceNotify(String lockKey, String lockToken)
    {
        try
        {
            redisCache.redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockToken);
        }
        catch (Exception e)
        {
            log.warn("微信余额提醒锁释放失败: lockKey={}, err={}", lockKey, e.getMessage());
        }
    }

    private boolean isBalanceReminderTrigger(AidUserProfile profile, WechatNotifyConfig config,
                                             BigDecimal requiredAmount)
    {
        BigDecimal balance = profile.getBalance() == null ? BigDecimal.ZERO : profile.getBalance();
        BigDecimal threshold = config.getBalanceReminderThreshold() == null
                ? BigDecimal.ZERO : config.getBalanceReminderThreshold();
        if (balance.compareTo(threshold) < 0)
        {
            return true;
        }
        return requiredAmount != null && requiredAmount.compareTo(BigDecimal.ZERO) > 0
                && balance.compareTo(requiredAmount) < 0;
    }

    private void consumeBalanceReminder(Long userId)
    {
        userProfileService.update(Wrappers.<AidUserProfile>lambdaUpdate()
                .eq(AidUserProfile::getUserId, userId)
                .eq(AidUserProfile::getDelFlag, DEL_FLAG_NORMAL)
                .set(AidUserProfile::getBalanceReminderAvailable, 0)
                .set(AidUserProfile::getUpdateTime, DateUtils.getNowDate())
                .set(AidUserProfile::getUpdateBy, "system"));
    }

    private String resolveOpenid(Long userId)
    {
        if (Objects.isNull(userId))
        {
            return null;
        }
        AidUserSocial social = userSocialService.getOne(Wrappers.<AidUserSocial>lambdaQuery()
                .select(AidUserSocial::getId, AidUserSocial::getOpenid)
                .eq(AidUserSocial::getUserId, userId)
                .eq(AidUserSocial::getPlatformSource, PLATFORM_WECHAT)
                .eq(AidUserSocial::getDelFlag, DEL_FLAG_NORMAL)
                .isNotNull(AidUserSocial::getOpenid)
                .last("limit 1"), false);
        return Objects.isNull(social) ? null : social.getOpenid();
    }

    private AidExtractTask loadTask(Long taskId)
    {
        if (Objects.isNull(taskId))
        {
            return null;
        }
        return extractTaskService.selectAidExtractTaskById(taskId);
    }

    private boolean isPushableFullBatch(AidExtractTask task)
    {
        if (Objects.isNull(task) || StrUtil.isBlank(task.getTaskType()))
        {
            return false;
        }
        if (!PUSHABLE_TASK_TYPES.contains(task.getTaskType()))
        {
            return false;
        }
        JsonNode snapshot = readSnapshot(task.getInputSnapshot());
        String taskType = task.getTaskType();
        if ("asset_extract".equals(taskType))
        {
            return isFullAssetExtract(snapshot);
        }
        if (resolveTotalCount(task) <= 1)
        {
            return false;
        }
        if ("storyboard_script_batch".equals(taskType))
        {
            return !snapshot.path("selective").asBoolean(false)
                    && idsMatchCount(snapshot.path("sceneIds"), countScenes(task));
        }
        if ("storyboard_image_prompt_batch".equals(taskType)
                || "storyboard_video_prompt_batch".equals(taskType)
                || "storyboard_image_generate".equals(taskType)
                || "storyboard_video_generate".equals(taskType))
        {
            return storyboardIdsMatchFullScope(snapshot, countStoryboards(task));
        }
        if ("form_generate_batch".equals(taskType))
        {
            return formGenerateFull(snapshot, task);
        }
        if ("form_image_batch".equals(taskType))
        {
            return idsMatchCount(snapshot.path("formIds"), countForms(task));
        }
        if ("form_card_image_batch".equals(taskType))
        {
            return idsMatchCount(snapshot.path("imageIds"), countFormImages(task));
        }
        return false;
    }

    private boolean isFullAssetExtract(JsonNode snapshot)
    {
        JsonNode typesNode = snapshot.path("extractTypes");
        if (!typesNode.isArray())
        {
            return false;
        }
        List<String> types = new ArrayList<>();
        for (JsonNode node : typesNode)
        {
            types.add(node.asText(""));
        }
        return types.contains("character") && types.contains("scene") && types.contains("prop");
    }

    private boolean formGenerateFull(JsonNode snapshot, AidExtractTask task)
    {
        List<Long> ids = readIds(snapshot.path("assetIds"));
        if (CollectionUtil.isEmpty(ids))
        {
            return false;
        }
        AidRolePropScene first = rolePropSceneService.getById(ids.get(0));
        if (Objects.isNull(first) || StrUtil.isBlank(first.getAssetType()))
        {
            return false;
        }
        long total = rolePropSceneService.count(Wrappers.<AidRolePropScene>lambdaQuery()
                .eq(AidRolePropScene::getProjectId, task.getProjectId())
                .eq(AidRolePropScene::getEpisodeId, task.getEpisodeId())
                .eq(AidRolePropScene::getUserId, task.getUserId())
                .eq(AidRolePropScene::getAssetType, first.getAssetType())
                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));
        return total > 1 && ids.size() == total;
    }

    private long countStoryboards(AidExtractTask task)
    {
        return storyboardService.count(Wrappers.<com.aid.aid.domain.AidStoryboard>lambdaQuery()
                .eq(com.aid.aid.domain.AidStoryboard::getProjectId, task.getProjectId())
                .eq(com.aid.aid.domain.AidStoryboard::getEpisodeId, task.getEpisodeId())
                .eq(com.aid.aid.domain.AidStoryboard::getUserId, task.getUserId())
                .eq(com.aid.aid.domain.AidStoryboard::getDelFlag, DEL_FLAG_NORMAL));
    }

    private long countScenes(AidExtractTask task)
    {
        return rolePropSceneService.count(Wrappers.<AidRolePropScene>lambdaQuery()
                .eq(AidRolePropScene::getProjectId, task.getProjectId())
                .eq(AidRolePropScene::getEpisodeId, task.getEpisodeId())
                .eq(AidRolePropScene::getUserId, task.getUserId())
                .eq(AidRolePropScene::getAssetType, "scene")
                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));
    }

    private long countForms(AidExtractTask task)
    {
        return rolePropSceneFormService.count(Wrappers.<AidRolePropSceneForm>lambdaQuery()
                .eq(AidRolePropSceneForm::getProjectId, task.getProjectId())
                .eq(AidRolePropSceneForm::getEpisodeId, task.getEpisodeId())
                .eq(AidRolePropSceneForm::getUserId, task.getUserId())
                .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL));
    }

    private long countFormImages(AidExtractTask task)
    {
        return rolePropSceneFormImageService.count(Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                .eq(AidRolePropSceneFormImage::getProjectId, task.getProjectId())
                .eq(AidRolePropSceneFormImage::getEpisodeId, task.getEpisodeId())
                .eq(AidRolePropSceneFormImage::getUserId, task.getUserId())
                .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL));
    }

    private boolean idsMatchCount(JsonNode idsNode, long total)
    {
        List<Long> ids = readIds(idsNode);
        return total > 1 && CollectionUtil.isNotEmpty(ids) && ids.size() == total;
    }

    private boolean storyboardIdsMatchFullScope(JsonNode snapshot, long total)
    {
        if (idsMatchCount(snapshot.path("storyboardIds"), total))
        {
            return true;
        }
        JsonNode allShots = snapshot.path("allShots");
        if (!allShots.isArray())
        {
            return false;
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : allShots)
        {
            JsonNode storyboardId = node.path("storyboardId");
            if (storyboardId.canConvertToLong())
            {
                Long id = storyboardId.asLong();
                if (!ids.contains(id))
                {
                    ids.add(id);
                }
            }
        }
        return total > 1 && ids.size() == total;
    }

    private List<Long> readIds(JsonNode idsNode)
    {
        List<Long> ids = new ArrayList<>();
        if (idsNode == null || !idsNode.isArray())
        {
            return ids;
        }
        for (JsonNode node : idsNode)
        {
            if (node.canConvertToLong())
            {
                ids.add(node.asLong());
            }
        }
        return ids;
    }

    private JsonNode readSnapshot(String inputSnapshot)
    {
        return readJson(inputSnapshot);
    }

    private JsonNode readJson(String json)
    {
        try
        {
            return OBJECT_MAPPER.readTree(StrUtil.blankToDefault(json, "{}"));
        }
        catch (Exception e)
        {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private int resolveTotalCount(AidExtractTask task)
    {
        if (task.getTotalCount() != null && task.getTotalCount() > 0)
        {
            return task.getTotalCount();
        }
        JsonNode snapshot = readSnapshot(task.getInputSnapshot());
        for (String name : List.of("storyboardIds", "sceneIds", "assetIds", "formIds", "imageIds", "extractTypes"))
        {
            JsonNode node = snapshot.path(name);
            if (node.isArray())
            {
                return node.size();
            }
        }
        return 0;
    }

    private String clientMsgId(String eventType, Long userId, Long bizId, String status)
    {
        return StrUtil.sub("aid_" + eventType + "_" + userId + "_" + bizId + "_" + status, 0, 120);
    }

    private String balanceRunStatus()
    {
        return "BALANCE_" + System.currentTimeMillis();
    }

    private String startRunStatus(AidExtractTask task)
    {
        Date updateTime = task.getUpdateTime() != null ? task.getUpdateTime() : task.getCreateTime();
        long runMillis = updateTime == null ? 0L : updateTime.getTime();
        return "STARTED_" + runMillis;
    }

    private String terminalRunStatus(AidExtractTask task, String status)
    {
        Date updateTime = task.getUpdateTime() != null ? task.getUpdateTime() : task.getCreateTime();
        long runMillis = updateTime == null ? 0L : updateTime.getTime();
        return status + "_" + runMillis;
    }

    private String buildJumpUrl(String baseUrl, Long taskId, String bizType)
    {
        if (StrUtil.isBlank(baseUrl))
        {
            return null;
        }
        String sep = baseUrl.contains("?") ? "&" : "?";
        StringBuilder url = new StringBuilder(baseUrl);
        if (taskId != null)
        {
            url.append(sep).append("taskId=").append(taskId);
            sep = "&";
        }
        if (StrUtil.isNotBlank(bizType))
        {
            url.append(sep).append("bizType=").append(bizType);
        }
        return url.toString();
    }

    /**
     * 公众号推送任务展示名：项目名·第N集·任务名（如"斗破苍穹·第3集·分镜脚本生成"），
     * 让用户在公众号里一眼知道是哪个项目哪一步的生成任务，而不是只有"分镜脚本生成"几个字。
     * 微信 thing 字段上限 20 字：超长时先把项目名截短（保留前缀+…），仍放不下则去掉项目名，
     * 保证任务名完整展示优先。仅影响公众号文案，SSE 推送内容不受影响。
     */
    private String taskDisplayName(AidExtractTask task)
    {
        String taskLabel = stepName(task);
        if (Objects.isNull(task))
        {
            return taskLabel;
        }
        String projectName = resolveProjectName(task.getProjectId());
        String episodeLabel = resolveEpisodeLabel(task.getEpisodeId());
        // 任务段：第N集·任务名（电影无剧集段）
        String suffix = StrUtil.isBlank(episodeLabel) ? taskLabel : episodeLabel + "·" + taskLabel;
        if (StrUtil.isBlank(projectName))
        {
            return cutByCodePoint(suffix, WECHAT_FIELD_MAX_LEN);
        }
        // 项目名可用预算 = 上限 - 任务段长度 - 1个分隔符
        int budget = WECHAT_FIELD_MAX_LEN - codePointLength(suffix) - 1;
        if (budget < 2)
        {
            // 项目名放不下，退回 第N集·任务名
            return cutByCodePoint(suffix, WECHAT_FIELD_MAX_LEN);
        }
        String shownProject = codePointLength(projectName) <= budget
                ? projectName
                : cutByCodePoint(projectName, budget - 1) + "…";
        return shownProject + "·" + suffix;
    }

    /** 查询项目名称（只取主键和项目名，勿随意增列）；查不到时返回空串，推送文案退化为不带项目前缀 */
    private String resolveProjectName(Long projectId)
    {
        if (Objects.isNull(projectId) || projectId <= 0)
        {
            return "";
        }
        try
        {
            AidComicProject project = comicProjectService.getOne(Wrappers.<AidComicProject>lambdaQuery()
                    .select(AidComicProject::getId, AidComicProject::getProjectName)
                    .eq(AidComicProject::getId, projectId)
                    .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL)
                    .last("limit 1"), false);
            return Objects.isNull(project) ? "" : StrUtil.trimToEmpty(project.getProjectName());
        }
        catch (Exception e)
        {
            log.warn("微信推送查询项目名失败: projectId={}, err={}", projectId, e.getMessage());
            return "";
        }
    }

    /** 查询剧集序号拼"第N集"（只取主键和集号，勿随意增列）；电影(episodeId=0)或查不到时返回空串 */
    private String resolveEpisodeLabel(Long episodeId)
    {
        if (Objects.isNull(episodeId) || episodeId <= 0)
        {
            return "";
        }
        try
        {
            AidComicEpisode episode = comicEpisodeService.getOne(Wrappers.<AidComicEpisode>lambdaQuery()
                    .select(AidComicEpisode::getId, AidComicEpisode::getEpisodeNo)
                    .eq(AidComicEpisode::getId, episodeId)
                    .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL)
                    .last("limit 1"), false);
            if (Objects.isNull(episode) || Objects.isNull(episode.getEpisodeNo()))
            {
                return "";
            }
            return "第" + episode.getEpisodeNo() + "集";
        }
        catch (Exception e)
        {
            log.warn("微信推送查询剧集失败: episodeId={}, err={}", episodeId, e.getMessage());
            return "";
        }
    }

    /** 按 Unicode 码点计数，与微信模板字段字数口径一致（emoji 等增补字符按1个字算） */
    private int codePointLength(String value)
    {
        return StrUtil.isBlank(value) ? 0 : value.codePointCount(0, value.length());
    }

    /** 按码点安全截断，避免截断 emoji 代理对产生乱码 */
    private String cutByCodePoint(String value, int max)
    {
        if (StrUtil.isBlank(value) || max <= 0)
        {
            return "";
        }
        if (value.codePointCount(0, value.length()) <= max)
        {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    private String stepName(AidExtractTask task)
    {
        if (Objects.isNull(task))
        {
            return "内容生成";
        }
        String taskType = task.getTaskType();
        JsonNode snapshot = readSnapshot(task.getInputSnapshot());
        String chainType = snapshot.path("chainNext").path("type").asText("");
        if ("storyboard_image_prompt_batch".equals(taskType))
        {
            return "image".equals(chainType) ? "分镜出图提示词+出图" : "分镜出图提示词";
        }
        if ("storyboard_video_prompt_batch".equals(taskType))
        {
            return switch (chainType)
            {
                case "video_image" -> "图生视频提示词+出片";
                case "video_grid" -> "宫格视频提示词+出片";
                case "video" -> "多参视频提示词+出片";
                default -> "分镜视频提示词";
            };
        }
        return stepName(taskType);
    }

    private String stepName(String taskType)
    {
        Map<String, String> names = Map.ofEntries(
                Map.entry("asset_extract", "角色场景道具提取"),
                Map.entry("form_generate_batch", "形态描述生成"),
                Map.entry("form_image_batch", "形态图生成"),
                Map.entry("form_card_image_batch", "角色设定卡生成"),
                Map.entry("storyboard_script_batch", "分镜脚本生成"),
                Map.entry("storyboard_image_prompt_batch", "分镜出图提示词"),
                Map.entry("storyboard_video_prompt_batch", "分镜视频提示词"),
                Map.entry("storyboard_image_generate", "分镜图生成"),
                Map.entry("storyboard_video_generate", "分镜视频生成"),
                Map.entry("storyboard_audio_generate", "批量配音"),
                Map.entry("storyboard_lip_sync_generate", "批量对口型")
        );
        return names.getOrDefault(taskType, "内容生成");
    }

    private String effectiveTerminalStatus(AidExtractTask task)
    {
        String status = task.getStatus();
        // 有些批量任务业务上保持 SUCCEEDED，但结果里仍可能存在失败项，推送应按部分失败告知用户。
        if (STATUS_SUCCEEDED.equals(status) && resultHasFailure(task))
        {
            return STATUS_PARTIAL_FAILED;
        }
        return status;
    }

    private boolean resultHasFailure(AidExtractTask task)
    {
        JsonNode result = readJson(task.getResultData());
        if (result.path("chainFailed").asBoolean(false))
        {
            return true;
        }
        if (result.path("failCount").asInt(0) > 0)
        {
            return true;
        }
        JsonNode failedItems = result.path("failedItems");
        if (failedItems.isArray() && failedItems.size() > 0)
        {
            return true;
        }
        JsonNode failures = result.path("failures");
        return failures.isArray() && failures.size() > 0;
    }

    /**
     * 公众号推送金额文案：系统内部金额单位是积分，
     * 微信模板 amount 字段带 ￥ 符号按元展示，这里统一把积分换算成元并保留两位小数，
     * 避免把积分数值直接当成元误导用户。仅影响公众号文案，SSE 与接口返回口径不变。
     */
    private String amountDataText(BigDecimal actual, BigDecimal fallback)
    {
        BigDecimal credits = actual != null ? actual : fallback;
        if (credits == null || credits.compareTo(BigDecimal.ZERO) < 0)
        {
            credits = BigDecimal.ZERO;
        }
        // 积分 ÷ aid_config 模型基础倍率 = 元，四舍五入保留两位小数。
        BigDecimal yuan = credits.divide(billingPriceMultiplierService.getGlobalMultiplier(),
                2, RoundingMode.HALF_UP);
        return "￥" + yuan.toPlainString();
    }

    private String balanceAmountText(Long userId)
    {
        AidUserProfile profile = userProfileService.getByUserId(userId);
        BigDecimal balance = Objects.isNull(profile) || profile.getBalance() == null
                ? BigDecimal.ZERO : profile.getBalance();
        return amountDataText(balance, BigDecimal.ZERO);
    }

    private String yuanAmountText(BigDecimal amount)
    {
        BigDecimal safeAmount = amount == null || amount.compareTo(BigDecimal.ZERO) < 0
                ? BigDecimal.ZERO : amount;
        return "￥" + safeAmount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String resolveRefundUser(Long userId)
    {
        try
        {
            SysUser user = sysUserService.selectUserById(userId);
            if (Objects.nonNull(user))
            {
                if (StrUtil.isNotBlank(user.getNickName()))
                {
                    return user.getNickName();
                }
                if (StrUtil.isNotBlank(user.getUserName()))
                {
                    return user.getUserName();
                }
            }
        }
        catch (Exception e)
        {
            log.warn("退款用户名称查询失败: userId={}, err={}", userId, e.getMessage());
        }
        return "用户" + userId;
    }

    private String nowText()
    {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private Map<String, String> sampleData(String eventType)
    {
        Map<String, String> data = new LinkedHashMap<>();
        if (WechatNotifyConfigServiceImpl.EVENT_BALANCE_INSUFFICIENT.equals(eventType))
        {
            data.put("accountName", "AID账户");
            data.put("currentBalance", "￥0.50");
            data.put("alarmTime", nowText());
            return data;
        }
        if (WechatNotifyConfigServiceImpl.EVENT_BATCH_SUCCEEDED.equals(eventType))
        {
            data.put("projectName", "示例项目·第1集·分镜图生成");
            data.put("finishTime", nowText());
            return data;
        }
        if (WechatNotifyConfigServiceImpl.EVENT_BATCH_FAILED.equals(eventType))
        {
            data.put("productName", "示例项目·第1集·分镜视频生成");
            data.put("orderAmount", "￥0.10");
            data.put("failureTime", nowText());
            return data;
        }
        if (WechatNotifyConfigServiceImpl.EVENT_AUDIT_RESULT.equals(eventType))
        {
            data.put("projectName", "示例项目·第1集");
            data.put("finishTime", nowText());
            data.put("auditResult", "审核通过");
            return data;
        }
        if (WechatNotifyConfigServiceImpl.EVENT_ORDER_REFUND.equals(eventType))
        {
            data.put("orderName", "积分充值订单");
            data.put("orderNo", "20260716123456789012");
            data.put("refundReason", "后台运营退款");
            data.put("refundAmount", "￥10.00");
            data.put("refundUser", "示例用户");
            return data;
        }
        data.put("serviceProject", "示例项目·第1集·分镜脚本生成");
        data.put("startTime", nowText());
        return data;
    }
}
