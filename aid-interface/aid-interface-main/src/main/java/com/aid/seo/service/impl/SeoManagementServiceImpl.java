package com.aid.seo.service.impl;

import com.aid.aid.domain.AidSeoPage;
import com.aid.aid.domain.AidSeoSubmission;
import com.aid.aid.domain.AidSeoSubmissionLog;
import com.aid.aid.mapper.AidSeoPageMapper;
import com.aid.aid.mapper.AidSeoSubmissionLogMapper;
import com.aid.aid.mapper.AidSeoSubmissionMapper;
import com.aid.aid.service.IAidConfigService;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.exception.ServiceException;
import com.aid.seo.model.SeoModels;
import com.aid.seo.security.SeoSecretCodec;
import com.aid.seo.service.SeoManagementService;
import com.aid.seo.service.SeoPageContributor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeoManagementServiceImpl implements SeoManagementService {
    private static final String CONFIG_CATEGORY = "seo";
    private static final String PROVIDER_BAIDU = "BAIDU";
    private static final String CHANNEL_API = "API";
    private static final String CHANNEL_MANUAL = "MANUAL";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_RETRY = "RETRY";
    private static final String STATUS_INVALID = "INVALID";
    private static final String STATUS_BLOCKED = "BLOCKED";
    private static final String ENABLED = "0";
    private static final String DISABLED = "1";
    private static final String NORMAL = "0";
    private static final String LOCK_KEY = "seo:baidu:submission:lock";
    private static final String BAIDU_ENDPOINT = "http://data.zz.baidu.com/urls";
    private static final int MAX_SITEMAP_URLS = 50_000;
    private static final int MAX_SITEMAP_BYTES = 10 * 1024 * 1024;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final AidSeoPageMapper pageMapper;
    private final AidSeoSubmissionMapper submissionMapper;
    private final AidSeoSubmissionLogMapper logMapper;
    private final ConfigService configService;
    private final IAidConfigService aidConfigService;
    private final SeoSecretCodec secretCodec;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;
    private final List<SeoPageContributor> contributors;

    @Override
    public SeoModels.Settings getSettings() {
        Map<String, String> values = configValues(CONFIG_CATEGORY);
        Map<String, String> basic = configValues("basic");
        SeoModels.Settings result = new SeoModels.Settings();
        result.setSiteUrl(normalizeSiteUrl(values.get("site_url"), false));
        result.setSiteName(firstNotBlank(values.get("site_name"), basic.get("site_name"), "AID"));
        result.setTitleSuffix(firstNotBlank(values.get("title_suffix"), result.getSiteName()));
        result.setDefaultDescription(firstNotBlank(values.get("default_description"), basic.get("site_description")));
        result.setDefaultKeywords(firstNotBlank(values.get("default_keywords"), basic.get("site_keywords")));
        result.setBaiduEnabled(booleanValue(values.get("baidu_enabled"), false));
        result.setBaiduSite(normalizeSiteUrl(firstNotBlank(values.get("baidu_site"), result.getSiteUrl()), false));
        result.setBaiduTokenConfigured(isEncryptedSecret(values.get("baidu_token")));
        result.setSubmitBatchSize(intValue(values.get("submit_batch_size"), 100, 1, 2000));
        result.setRobotsDisallow(firstNotBlank(values.get("robots_disallow"),
                "/admin\n/create\n/login\n/user\n/profile\n/aid\n/prod-api"));
        result.setRobotsUrl(joinUrl(result.getSiteUrl(), "/robots.txt"));
        result.setSitemapUrl(joinUrl(result.getSiteUrl(), "/sitemap.xml"));
        result.setRobotsPreview(buildRobots(result));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSettings(SeoModels.SettingsSave request, String operator) {
        if (request == null) {
            throw new ServiceException("SEO 配置不能为空");
        }
        SeoModels.Settings previous = getSettings();
        String siteUrl = normalizeSiteUrl(request.getSiteUrl(), true);
        String baiduSite = normalizeSiteUrl(firstNotBlank(request.getBaiduSite(), siteUrl), true);
        assertSameOrigin(siteUrl, baiduSite, "百度站点必须与网站地址同源");
        int batchSize = request.getSubmitBatchSize() == null ? 100 : request.getSubmitBatchSize();
        if (batchSize < 1 || batchSize > 2000) {
            throw new ServiceException("单批提交数量必须在 1 到 2000 之间");
        }
        upsertConfig("site_url", siteUrl);
        upsertConfig("site_name", trim(request.getSiteName(), 80));
        upsertConfig("title_suffix", trim(request.getTitleSuffix(), 80));
        upsertConfig("default_description", trim(request.getDefaultDescription(), 300));
        upsertConfig("default_keywords", trim(request.getDefaultKeywords(), 500));
        upsertConfig("baidu_enabled", Boolean.TRUE.equals(request.getBaiduEnabled()) ? "true" : "false");
        upsertConfig("baidu_site", baiduSite);
        upsertConfig("submit_batch_size", String.valueOf(batchSize));
        upsertConfig("robots_disallow", normalizeRobotsRules(request.getRobotsDisallow()));
        if (Boolean.TRUE.equals(request.getClearBaiduToken())) {
            upsertConfig("baidu_token", "");
        } else if (request.getBaiduToken() != null && !request.getBaiduToken().isBlank()
                && !request.getBaiduToken().contains("****")) {
            String token = request.getBaiduToken().trim();
            if (!token.matches("[A-Za-z0-9_-]{8,128}")) {
                throw new ServiceException("百度准入密钥格式不正确");
            }
            upsertConfig("baidu_token", secretCodec.encrypt(token));
        }
        refreshCanonicalUrls(operator);
        refreshDerivedMetadata(previous, operator);
        ensureBuiltInPages(operator);
    }

    @Override
    public SeoModels.Overview overview() {
        SeoModels.Overview result = new SeoModels.Overview();
        result.setTotalPages(countPages(null));
        result.setIndexablePages(countPages(true));
        result.setPendingPages(countSubmissions(Set.of(STATUS_PENDING, STATUS_PROCESSING)));
        result.setAcceptedPages(countSubmissions(Set.of(STATUS_ACCEPTED)));
        result.setRetryPages(countSubmissions(Set.of(STATUS_RETRY, STATUS_INVALID, STATUS_BLOCKED)));
        Map<String, String> config = configValues(CONFIG_CATEGORY);
        result.setLastScanTime(epochDate(config.get("last_scan_epoch")));
        AidSeoSubmission latest = submissionMapper.selectOne(new LambdaQueryWrapper<AidSeoSubmission>()
                .eq(AidSeoSubmission::getProvider, PROVIDER_BAIDU)
                .eq(AidSeoSubmission::getChannel, CHANNEL_API)
                .eq(AidSeoSubmission::getDelFlag, NORMAL)
                .orderByDesc(AidSeoSubmission::getLastAttemptTime)
                .last("LIMIT 1"));
        if (latest != null) {
            result.setLastSubmitTime(latest.getLastAttemptTime());
            result.setProviderRemain(latest.getProviderRemain());
        }
        SeoModels.Settings settings = getSettings();
        result.setBaiduReady(Boolean.TRUE.equals(settings.getBaiduEnabled())
                && settings.getBaiduTokenConfigured()
                && settings.getBaiduSite() != null);
        return result;
    }

    @Override
    public SeoModels.PageResult page(SeoModels.PageQuery source) {
        SeoModels.PageQuery request = source == null ? new SeoModels.PageQuery() : source;
        int pageNum = intValue(String.valueOf(request.getPageNum()), 1, 1, Integer.MAX_VALUE);
        int pageSize = intValue(String.valueOf(request.getPageSize()), 20, 1, 100);
        LambdaQueryWrapper<AidSeoPage> query = new LambdaQueryWrapper<AidSeoPage>()
                .eq(AidSeoPage::getDelFlag, NORMAL);
        if (request.getIndexable() != null) {
            query.eq(AidSeoPage::getIndexable, request.getIndexable() ? 1 : 0);
        }
        if (notBlank(request.getSourceType())) {
            query.eq(AidSeoPage::getSourceType, request.getSourceType().trim().toUpperCase(Locale.ROOT));
        }
        if (notBlank(request.getKeyword())) {
            String keyword = request.getKeyword().trim();
            query.and(wrapper -> wrapper.like(AidSeoPage::getPageTitle, keyword)
                    .or().like(AidSeoPage::getCanonicalUrl, keyword)
                    .or().like(AidSeoPage::getMetaKeywords, keyword));
        }
        Set<Long> matchedSubmissionIds = submissionPageIds(request.getSubmitStatus());
        if (notBlank(request.getSubmitStatus())) {
            if (matchedSubmissionIds.isEmpty()) {
                return new SeoModels.PageResult();
            }
            query.in(AidSeoPage::getId, matchedSubmissionIds);
        }
        if (Boolean.TRUE.equals(request.getOnlyUnsubmitted())) {
            Set<Long> accepted = submissionPageIds(STATUS_ACCEPTED);
            if (!accepted.isEmpty()) {
                query.notIn(AidSeoPage::getId, accepted);
            }
        }
        query.orderByDesc(AidSeoPage::getUpdateTime).orderByDesc(AidSeoPage::getId);
        Page<AidSeoPage> page = pageMapper.selectPage(new Page<>(pageNum, pageSize), query);
        List<Long> pageIds = page.getRecords().stream().map(AidSeoPage::getId).toList();
        Map<Long, Map<String, AidSeoSubmission>> submissions = loadSubmissions(pageIds);
        SeoModels.PageResult result = new SeoModels.PageResult();
        result.setTotal(page.getTotal());
        result.setItems(page.getRecords().stream()
                .map(item -> toPageView(item, submissions.get(item.getId())))
                .toList());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SeoModels.PageView savePage(SeoModels.PageSave request, String operator) {
        if (request == null) {
            throw new ServiceException("页面信息不能为空");
        }
        SeoModels.Settings settings = getSettings();
        if (!notBlank(settings.getSiteUrl())) {
            throw new ServiceException("请先配置网站地址");
        }
        String pagePath = normalizePagePath(request.getPagePath(), settings.getSiteUrl());
        AidSeoPage existing = request.getId() == null ? null : pageMapper.selectById(request.getId());
        if (existing == null && notBlank(request.getSourceType()) && notBlank(request.getSourceId())) {
            existing = pageMapper.selectOne(new LambdaQueryWrapper<AidSeoPage>()
                    .eq(AidSeoPage::getSourceType, request.getSourceType().trim().toUpperCase(Locale.ROOT))
                    .eq(AidSeoPage::getSourceId, request.getSourceId().trim())
                    .eq(AidSeoPage::getDelFlag, NORMAL)
                    .last("LIMIT 1"));
        }
        if (existing == null) {
            existing = pageMapper.selectOne(new LambdaQueryWrapper<AidSeoPage>()
                    .eq(AidSeoPage::getPagePath, pagePath)
                    .eq(AidSeoPage::getDelFlag, NORMAL)
                    .last("LIMIT 1"));
        }
        Date now = new Date();
        AidSeoPage entity = existing == null ? new AidSeoPage() : existing;
        String oldHash = entity.getContentHash();
        entity.setSourceType(trim(firstNotBlank(request.getSourceType(), "MANUAL").toUpperCase(Locale.ROOT), 32));
        entity.setSourceId(trim(request.getSourceId(), 128));
        entity.setPagePath(pagePath);
        entity.setCanonicalUrl(joinUrl(settings.getSiteUrl(), pagePath));
        entity.setPageTitle(buildTitle(request.getPageTitle(), pagePath, settings));
        entity.setMetaDescription(buildDescription(request.getMetaDescription(), settings));
        entity.setMetaKeywords(buildKeywords(request.getMetaKeywords(), entity.getPageTitle(), settings));
        entity.setOgImageUrl(trim(request.getOgImageUrl(), 500));
        entity.setIndexable(Boolean.FALSE.equals(request.getIndexable()) ? 0 : 1);
        entity.setSitemapEnabled(Boolean.FALSE.equals(request.getSitemapEnabled()) ? 0 : 1);
        entity.setSourceUpdateTime(request.getSourceUpdateTime() != null
                ? request.getSourceUpdateTime()
                : existing == null ? now : existing.getSourceUpdateTime());
        entity.setLastSeenTime(now);
        entity.setStatus(DISABLED.equals(request.getStatus()) ? DISABLED : ENABLED);
        entity.setDelFlag(NORMAL);
        entity.setContentHash(contentHash(entity));
        entity.setUpdateBy(safeOperator(operator));
        entity.setUpdateTime(now);
        if (entity.getId() == null) {
            entity.setCreateBy(safeOperator(operator));
            entity.setCreateTime(now);
            pageMapper.insert(entity);
        } else {
            pageMapper.updateById(entity);
        }
        if (!Objects.equals(oldHash, entity.getContentHash())) {
            enqueue(entity, now);
        }
        return toPageView(entity, loadSubmissions(List.of(entity.getId())).get(entity.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archivePage(Long pageId, String operator) {
        AidSeoPage page = requirePage(pageId);
        Date now = new Date();
        pageMapper.update(null, new LambdaUpdateWrapper<AidSeoPage>()
                .eq(AidSeoPage::getId, pageId)
                .set(AidSeoPage::getStatus, DISABLED)
                .set(AidSeoPage::getIndexable, 0)
                .set(AidSeoPage::getSitemapEnabled, 0)
                .set(AidSeoPage::getUpdateBy, safeOperator(operator))
                .set(AidSeoPage::getUpdateTime, now));
        submissionMapper.update(null, new LambdaUpdateWrapper<AidSeoSubmission>()
                .eq(AidSeoSubmission::getPageId, page.getId())
                .eq(AidSeoSubmission::getDelFlag, NORMAL)
                .set(AidSeoSubmission::getSubmitStatus, STATUS_BLOCKED)
                .set(AidSeoSubmission::getLastErrorCode, "PAGE_ARCHIVED")
                .set(AidSeoSubmission::getLastErrorMessage, "页面已停用，不再提交")
                .set(AidSeoSubmission::getUpdateTime, now));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int scan(String triggerType, String operator) {
        ensureBuiltInPages(operator);
        int changed = 0;
        for (SeoPageContributor contributor : contributors) {
            try {
                String sourceType = trim(firstNotBlank(contributor.sourceType()), 32);
                if (!notBlank(sourceType)) {
                    throw new ServiceException("SEO 页面提供器缺少来源类型");
                }
                sourceType = sourceType.toUpperCase(Locale.ROOT);
                if ("MANUAL".equals(sourceType) || "STATIC".equals(sourceType)) {
                    throw new ServiceException("SEO 页面提供器不能占用系统来源类型");
                }
                List<SeoModels.ContentCandidate> candidates = contributor.listIndexablePages();
                List<SeoModels.ContentCandidate> safeCandidates = candidates == null ? List.of() : candidates;
                Set<String> seenSourceIds = new HashSet<>();
                for (SeoModels.ContentCandidate candidate : safeCandidates) {
                    if (candidate == null || !notBlank(candidate.getSourceId())) {
                        log.warn("SEO 页面提供器忽略缺少 sourceId 的候选页面, provider={}",
                                contributor.getClass().getSimpleName());
                        continue;
                    }
                    candidate.setSourceType(sourceType);
                    seenSourceIds.add(candidate.getSourceId().trim());
                    savePage(candidate, operator);
                    changed++;
                }
                archiveMissingContributorPages(sourceType, seenSourceIds, operator);
            } catch (Exception ex) {
                log.error("SEO 页面提供器扫描失败, provider={}, error={}",
                        contributor.getClass().getSimpleName(), ex.getMessage());
            }
        }
        refreshCanonicalUrls(operator);
        upsertConfig("last_scan_epoch", String.valueOf(System.currentTimeMillis()));
        log.info("SEO 页面扫描完成, trigger={}, discovered={}", safeText(triggerType, 32), changed);
        return changed;
    }

    private void archiveMissingContributorPages(String sourceType, Set<String> seenSourceIds, String operator) {
        List<AidSeoPage> previous = pageMapper.selectList(new LambdaQueryWrapper<AidSeoPage>()
                .eq(AidSeoPage::getSourceType, sourceType)
                .eq(AidSeoPage::getStatus, ENABLED)
                .eq(AidSeoPage::getDelFlag, NORMAL));
        for (AidSeoPage page : previous) {
            if (!notBlank(page.getSourceId()) || !seenSourceIds.contains(page.getSourceId())) {
                archivePage(page.getId(), operator);
            }
        }
    }

    @Override
    public SeoModels.DispatchResult submit(List<Long> pageIds, String triggerType,
                                           Long operatorId, String operatorName) {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 10, TimeUnit.MINUTES);
            if (!acquired) {
                throw new ServiceException("SEO 提交任务正在执行，请稍后重试");
            }
            return doSubmit(pageIds, triggerType, operatorId, operatorName);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ServiceException("SEO 提交任务已中断");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int confirmManual(List<Long> pageIds, Long operatorId, String operatorName) {
        List<Long> ids = cleanIds(pageIds, 20);
        if (ids.isEmpty()) {
            throw new ServiceException("请选择要确认的页面，最多 20 条");
        }
        Date now = new Date();
        String batchNo = batchNo("MANUAL");
        int count = 0;
        for (AidSeoPage page : pageMapper.selectBatchIds(ids)) {
            if (!isEligible(page)) {
                continue;
            }
            AidSeoSubmission submission = getOrCreateSubmission(page.getId(), CHANNEL_MANUAL, now);
            updateSubmission(submission, STATUS_ACCEPTED, page.getContentHash(), null, now, now,
                    200, null, null, null);
            saveLog(batchNo, page, CHANNEL_MANUAL, "ADMIN_MANUAL", STATUS_ACCEPTED,
                    200, "管理员确认已在站长平台手动提交", null, null, operatorId, operatorName);
            count++;
        }
        return count;
    }

    @Override
    public List<SeoModels.LogView> logs(Long pageId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<AidSeoSubmissionLog> rows = logMapper.selectList(new LambdaQueryWrapper<AidSeoSubmissionLog>()
                .eq(pageId != null, AidSeoSubmissionLog::getPageId, pageId)
                .eq(AidSeoSubmissionLog::getDelFlag, NORMAL)
                .orderByDesc(AidSeoSubmissionLog::getCreateTime)
                .orderByDesc(AidSeoSubmissionLog::getId)
                .last("LIMIT " + safeLimit));
        return rows.stream().map(this::toLogView).toList();
    }

    @Override
    public SeoModels.MetaView meta(String rawPath) {
        String path;
        try {
            path = normalizePagePath(rawPath, firstNotBlank(getSettings().getSiteUrl(), "https://example.com"));
        } catch (Exception ex) {
            return null;
        }
        AidSeoPage page = pageMapper.selectOne(new LambdaQueryWrapper<AidSeoPage>()
                .eq(AidSeoPage::getPagePath, path)
                .eq(AidSeoPage::getStatus, ENABLED)
                .eq(AidSeoPage::getDelFlag, NORMAL)
                .last("LIMIT 1"));
        if (page == null) {
            return null;
        }
        SeoModels.MetaView result = new SeoModels.MetaView();
        result.setTitle(page.getPageTitle());
        result.setDescription(page.getMetaDescription());
        result.setKeywords(page.getMetaKeywords());
        result.setCanonicalUrl(page.getCanonicalUrl());
        result.setImageUrl(page.getOgImageUrl());
        result.setRobots(Objects.equals(page.getIndexable(), 1) ? "index,follow" : "noindex,nofollow");
        return result;
    }

    @Override
    public String robots() {
        return buildRobots(getSettings());
    }

    @Override
    public String sitemap() {
        List<AidSeoPage> rows = pageMapper.selectList(new LambdaQueryWrapper<AidSeoPage>()
                .eq(AidSeoPage::getIndexable, 1)
                .eq(AidSeoPage::getSitemapEnabled, 1)
                .eq(AidSeoPage::getStatus, ENABLED)
                .eq(AidSeoPage::getDelFlag, NORMAL)
                .isNotNull(AidSeoPage::getCanonicalUrl)
                .orderByDesc(AidSeoPage::getSourceUpdateTime)
                .last("LIMIT " + MAX_SITEMAP_URLS));
        String header = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n";
        String footer = "</urlset>\n";
        StringBuilder xml = new StringBuilder(1024 + rows.size() * 160).append(header);
        int bytes = header.getBytes(StandardCharsets.UTF_8).length;
        for (AidSeoPage page : rows) {
            if (!notBlank(page.getCanonicalUrl())) {
                continue;
            }
            StringBuilder entry = new StringBuilder(160)
                    .append("  <url><loc>").append(xmlEscape(page.getCanonicalUrl())).append("</loc>");
            Date modified = page.getSourceUpdateTime() == null ? page.getUpdateTime() : page.getSourceUpdateTime();
            if (modified != null) {
                entry.append("<lastmod>").append(Instant.ofEpochMilli(modified.getTime())).append("</lastmod>");
            }
            entry.append("</url>\n");
            int entryBytes = entry.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes + entryBytes + footer.length() >= MAX_SITEMAP_BYTES) {
                break;
            }
            xml.append(entry);
            bytes += entryBytes;
        }
        return xml.append(footer).toString();
    }

    @Override
    public void scheduledTick() {
        try {
            scan("SCHEDULE", "system");
            submit(List.of(), "SCHEDULE", null, "system");
        } catch (Exception ex) {
            log.error("SEO 定时扫描提交失败, error={}", ex.getMessage());
        }
    }

    private SeoModels.DispatchResult doSubmit(List<Long> requestedIds, String triggerType,
                                              Long operatorId, String operatorName) {
        SeoModels.Settings settings = getSettings();
        SeoModels.DispatchResult result = new SeoModels.DispatchResult();
        result.setBatchNo(batchNo("BAIDU"));
        if (!Boolean.TRUE.equals(settings.getBaiduEnabled())) {
            result.setMessage("百度 API 提交未启用");
            return result;
        }
        String encryptedToken = configValues(CONFIG_CATEGORY).get("baidu_token");
        if (!isEncryptedSecret(encryptedToken)) {
            result.setMessage("百度准入密钥未配置");
            return result;
        }
        String token;
        try {
            token = secretCodec.decrypt(encryptedToken);
        } catch (Exception ex) {
            result.setMessage("百度准入密钥无法解密，请在后台重新保存");
            return result;
        }
        List<AidSeoPage> pages = selectSubmissionPages(requestedIds, settings.getSubmitBatchSize());
        result.setSelected(pages.size());
        if (pages.isEmpty()) {
            result.setMessage("没有待提交页面");
            return result;
        }
        Date now = new Date();
        List<AidSeoPage> valid = new ArrayList<>();
        for (AidSeoPage page : pages) {
            AidSeoSubmission submission = getOrCreateSubmission(page.getId(), CHANNEL_API, now);
            if (!isEligible(page)) {
                updateSubmission(submission, STATUS_BLOCKED, null, nextRetry(submission, now), now, null,
                        null, "PAGE_NOT_INDEXABLE", "页面已停用或禁止索引", null);
                saveLog(result.getBatchNo(), page, CHANNEL_API, triggerType, STATUS_BLOCKED, null,
                        "提交前校验未通过", "PAGE_NOT_INDEXABLE", "页面已停用或禁止索引",
                        operatorId, operatorName);
                result.setRejected(result.getRejected() + 1);
                continue;
            }
            try {
                assertSameOrigin(settings.getBaiduSite(), page.getCanonicalUrl(), "页面不是当前百度站点 URL");
                valid.add(page);
                updateSubmission(submission, STATUS_PROCESSING, submission.getSubmittedHash(), null, now,
                        submission.getAcceptedTime(), null, null, null, submission.getProviderRemain());
            } catch (Exception ex) {
                updateSubmission(submission, STATUS_INVALID, null, null, now, null,
                        null, "NOT_SAME_SITE", safeText(ex.getMessage(), 500), null);
                saveLog(result.getBatchNo(), page, CHANNEL_API, triggerType, STATUS_INVALID, null,
                        "提交前校验未通过", "NOT_SAME_SITE", safeText(ex.getMessage(), 500),
                        operatorId, operatorName);
                result.setRejected(result.getRejected() + 1);
            }
        }
        if (valid.isEmpty()) {
            result.setMessage("所选页面均未通过提交前校验");
            return result;
        }
        try {
            String endpoint = BAIDU_ENDPOINT + "?site=" + encode(baiduSiteParameter(settings.getBaiduSite()))
                    + "&token=" + encode(token);
            String payload = valid.stream().map(AidSeoPage::getCanonicalUrl).collect(Collectors.joining("\n"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            applyBaiduResponse(result, valid, response.statusCode(), response.body(), triggerType,
                    operatorId, operatorName, now, token);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            applyTransportFailure(result, valid, "INTERRUPTED", "提交请求已中断", triggerType,
                    operatorId, operatorName, now);
        } catch (Exception ex) {
            applyTransportFailure(result, valid, "NETWORK_ERROR", sanitizeSecret(ex.getMessage(), token), triggerType,
                    operatorId, operatorName, now);
        }
        return result;
    }

    private void applyBaiduResponse(SeoModels.DispatchResult result, List<AidSeoPage> pages,
                                    int httpStatus, String body, String triggerType,
                                    Long operatorId, String operatorName, Date now, String token) {
        JsonNode json;
        boolean invalidResponse = false;
        try {
            json = objectMapper.readTree(body == null ? "{}" : body);
            if (json == null || !json.isObject()) {
                throw new IllegalArgumentException("Expected JSON object");
            }
        } catch (Exception ex) {
            json = objectMapper.createObjectNode();
            invalidResponse = true;
        }
        int success = Math.max(0, json.path("success").asInt(0));
        Integer remain = json.has("remain") && json.get("remain").canConvertToInt()
                ? json.get("remain").asInt() : null;
        result.setRemain(remain);
        Set<String> notSameSite = jsonArraySet(json.get("not_same_site"));
        Set<String> notValid = jsonArraySet(json.get("not_valid"));
        String providerCode = sanitizeSecret(json.path("error").asText(null), token);
        String providerMessage = sanitizeSecret(json.path("message").asText(null), token);
        boolean hasProviderError = notBlank(providerCode) && !"0".equals(providerCode);
        boolean quotaExceeded = "over quota".equalsIgnoreCase(providerMessage);
        boolean providerBlocked = httpStatus >= 400 && httpStatus < 500
                && httpStatus != 408 && httpStatus != 429 && !quotaExceeded;
        int acceptedBudget = httpStatus == 200 && !hasProviderError ? success : 0;
        String summary = "success=" + success + ", remain=" + (remain == null ? "unknown" : remain)
                + ", not_same_site=" + notSameSite.size() + ", not_valid=" + notValid.size()
                + (notBlank(providerCode) ? ", error=" + providerCode : "")
                + (notBlank(providerMessage) ? ", message=" + providerMessage : "")
                + (invalidResponse ? ", response=INVALID_RESPONSE" : "");
        String failureMessage = firstNotBlank(providerMessage, invalidResponse
                ? "百度返回了无法解析的响应，请检查网络或代理；原始内容未保存"
                : "百度未返回具体错误说明，请检查站点验证、准入密钥及提交配额");
        for (AidSeoPage page : pages) {
            AidSeoSubmission submission = getOrCreateSubmission(page.getId(), CHANNEL_API, now);
            String status;
            String code = null;
            String message = null;
            Date retryAt = null;
            Date acceptedAt = null;
            if (notSameSite.contains(page.getCanonicalUrl())) {
                status = STATUS_BLOCKED;
                code = "NOT_SAME_SITE";
                message = "百度拒绝了非本站 URL";
            } else if (notValid.contains(page.getCanonicalUrl())) {
                status = STATUS_INVALID;
                code = "NOT_VALID";
                message = "百度判定 URL 不合法";
            } else if (acceptedBudget > 0) {
                status = STATUS_ACCEPTED;
                acceptedBudget--;
                acceptedAt = now;
            } else if (providerBlocked) {
                status = STATUS_BLOCKED;
                code = "HTTP_" + httpStatus;
                message = failureMessage;
            } else {
                status = STATUS_RETRY;
                boolean quotaEmpty = quotaExceeded || (httpStatus == 200 && remain != null && remain == 0);
                code = quotaEmpty ? "QUOTA_EXHAUSTED" : httpStatus != 200 ? "HTTP_" + httpStatus
                        : invalidResponse ? "INVALID_RESPONSE" : "NOT_ACCEPTED";
                message = quotaEmpty ? firstNotBlank(providerMessage, "百度当日提交余额已用完") : failureMessage;
                retryAt = quotaEmpty ? tomorrow(now) : nextRetry(submission, now);
            }
            updateSubmission(submission, status,
                    STATUS_ACCEPTED.equals(status) ? page.getContentHash() : submission.getSubmittedHash(),
                    retryAt, now, acceptedAt, httpStatus, code, message, remain);
            saveLog(result.getBatchNo(), page, CHANNEL_API, triggerType, status, httpStatus,
                    summary, code, message, operatorId, operatorName);
            if (STATUS_ACCEPTED.equals(status)) {
                result.setAccepted(result.getAccepted() + 1);
            } else if (STATUS_RETRY.equals(status)) {
                result.setDeferred(result.getDeferred() + 1);
            } else {
                result.setRejected(result.getRejected() + 1);
            }
        }
        if (result.getAccepted() > 0) {
            result.setMessage(result.getAccepted() + " 条已被百度接口接收；接口接收不代表已建立索引");
        } else if (result.getDeferred() > 0) {
            result.setMessage("本批链接未被百度接收，已安排重试：" + failureMessage);
        } else {
            result.setMessage("本批链接未被百度接收：" + failureMessage + "；请查看提交记录并处理后重试");
        }
    }

    private void applyTransportFailure(SeoModels.DispatchResult result, List<AidSeoPage> pages,
                                       String code, String message, String triggerType,
                                       Long operatorId, String operatorName, Date now) {
        for (AidSeoPage page : pages) {
            AidSeoSubmission submission = getOrCreateSubmission(page.getId(), CHANNEL_API, now);
            Date retryAt = nextRetry(submission, now);
            updateSubmission(submission, STATUS_RETRY, submission.getSubmittedHash(), retryAt, now,
                    submission.getAcceptedTime(), null, code, message, submission.getProviderRemain());
            saveLog(result.getBatchNo(), page, CHANNEL_API, triggerType, STATUS_RETRY, null,
                    "百度提交请求失败，已安排重试", code, message, operatorId, operatorName);
            result.setDeferred(result.getDeferred() + 1);
        }
        result.setMessage("本批提交未完成，已记录并安排重试");
    }

    private List<AidSeoPage> selectSubmissionPages(List<Long> requestedIds, int batchSize) {
        List<Long> ids = cleanIds(requestedIds, batchSize);
        LambdaQueryWrapper<AidSeoPage> query = new LambdaQueryWrapper<AidSeoPage>()
                .eq(AidSeoPage::getIndexable, 1)
                .eq(AidSeoPage::getStatus, ENABLED)
                .eq(AidSeoPage::getDelFlag, NORMAL);
        if (!ids.isEmpty()) {
            query.in(AidSeoPage::getId, ids);
        } else {
            Date stale = Date.from(Instant.now().minus(30, ChronoUnit.MINUTES));
            submissionMapper.update(null, new LambdaUpdateWrapper<AidSeoSubmission>()
                    .eq(AidSeoSubmission::getProvider, PROVIDER_BAIDU)
                    .eq(AidSeoSubmission::getChannel, CHANNEL_API)
                    .eq(AidSeoSubmission::getSubmitStatus, STATUS_PROCESSING)
                    .lt(AidSeoSubmission::getLastAttemptTime, stale)
                    .set(AidSeoSubmission::getSubmitStatus, STATUS_RETRY)
                    .set(AidSeoSubmission::getNextRetryTime, new Date()));
            // 旧版本把百度 JSON 中的数字 error 当作完整说明，导致配置修复后永久停留在 BLOCKED。
            // 只重排这一类可精确识别的历史记录；新版本保存文字说明后不会再次命中。
            submissionMapper.update(null, new LambdaUpdateWrapper<AidSeoSubmission>()
                    .eq(AidSeoSubmission::getProvider, PROVIDER_BAIDU)
                    .eq(AidSeoSubmission::getChannel, CHANNEL_API)
                    .eq(AidSeoSubmission::getSubmitStatus, STATUS_BLOCKED)
                    .and(wrapper -> wrapper
                            .eq(AidSeoSubmission::getLastErrorCode, "HTTP_400")
                            .eq(AidSeoSubmission::getLastErrorMessage, "400")
                            .or(nested -> nested.eq(AidSeoSubmission::getLastErrorCode, "HTTP_401")
                                    .eq(AidSeoSubmission::getLastErrorMessage, "401"))
                            .or(nested -> nested.eq(AidSeoSubmission::getLastErrorCode, "HTTP_404")
                                    .eq(AidSeoSubmission::getLastErrorMessage, "404")))
                    .eq(AidSeoSubmission::getDelFlag, NORMAL)
                    .set(AidSeoSubmission::getSubmitStatus, STATUS_RETRY)
                    .set(AidSeoSubmission::getNextRetryTime, new Date())
                    .set(AidSeoSubmission::getUpdateBy, "system")
                    .set(AidSeoSubmission::getUpdateTime, new Date()));
            List<AidSeoSubmission> pending = submissionMapper.selectList(new LambdaQueryWrapper<AidSeoSubmission>()
                    .eq(AidSeoSubmission::getProvider, PROVIDER_BAIDU)
                    .eq(AidSeoSubmission::getChannel, CHANNEL_API)
                    .in(AidSeoSubmission::getSubmitStatus, STATUS_PENDING, STATUS_RETRY)
                    .and(wrapper -> wrapper.isNull(AidSeoSubmission::getNextRetryTime)
                            .or().le(AidSeoSubmission::getNextRetryTime, new Date()))
                    .eq(AidSeoSubmission::getDelFlag, NORMAL)
                    .orderByAsc(AidSeoSubmission::getNextRetryTime)
                    .orderByAsc(AidSeoSubmission::getId)
                    .last("LIMIT " + batchSize));
            ids = pending.stream().map(AidSeoSubmission::getPageId).distinct().toList();
            if (ids.isEmpty()) {
                return List.of();
            }
            query.in(AidSeoPage::getId, ids);
        }
        query.orderByAsc(AidSeoPage::getId).last("LIMIT " + batchSize);
        return pageMapper.selectList(query);
    }

    private void ensureBuiltInPages(String operator) {
        SeoModels.Settings settings = getSettings();
        if (!notBlank(settings.getSiteUrl())) {
            return;
        }
        if (pageMapper.selectCount(new LambdaQueryWrapper<AidSeoPage>()
                .eq(AidSeoPage::getPagePath, "/").eq(AidSeoPage::getDelFlag, NORMAL)) == 0) {
            SeoModels.PageSave home = new SeoModels.PageSave();
            home.setSourceType("STATIC");
            home.setSourceId("home");
            home.setPagePath("/");
            home.setPageTitle(settings.getSiteName());
            home.setMetaDescription(settings.getDefaultDescription());
            home.setMetaKeywords(settings.getDefaultKeywords());
            savePage(home, operator);
        }
        if (pageMapper.selectCount(new LambdaQueryWrapper<AidSeoPage>()
                .eq(AidSeoPage::getPagePath, "/faq").eq(AidSeoPage::getDelFlag, NORMAL)) == 0) {
            SeoModels.PageSave faq = new SeoModels.PageSave();
            faq.setSourceType("STATIC");
            faq.setSourceId("faq");
            faq.setPagePath("/faq");
            faq.setPageTitle("常见问题");
            faq.setMetaDescription("常见问题、使用说明与帮助中心");
            savePage(faq, operator);
        }
    }

    private void refreshCanonicalUrls(String operator) {
        String siteUrl = getSettings().getSiteUrl();
        if (!notBlank(siteUrl)) {
            return;
        }
        Date now = new Date();
        for (AidSeoPage page : pageMapper.selectList(new LambdaQueryWrapper<AidSeoPage>()
                .eq(AidSeoPage::getDelFlag, NORMAL))) {
            String canonical = joinUrl(siteUrl, page.getPagePath());
            if (Objects.equals(canonical, page.getCanonicalUrl())) {
                continue;
            }
            page.setCanonicalUrl(canonical);
            page.setContentHash(contentHash(page));
            page.setUpdateBy(safeOperator(operator));
            page.setUpdateTime(now);
            pageMapper.updateById(page);
            enqueue(page, now);
        }
    }

    private void refreshDerivedMetadata(SeoModels.Settings previous, String operator) {
        SeoModels.Settings current = getSettings();
        Date now = new Date();
        for (AidSeoPage page : pageMapper.selectList(new LambdaQueryWrapper<AidSeoPage>()
                .eq(AidSeoPage::getDelFlag, NORMAL))) {
            String baseTitle = stripTitleSuffix(page.getPageTitle(), previous.getTitleSuffix());
            String nextTitle = buildTitle(baseTitle, page.getPagePath(), current);
            String nextDescription = page.getMetaDescription();
            if (!notBlank(nextDescription)
                    || Objects.equals(nextDescription, previous.getDefaultDescription())) {
                nextDescription = buildDescription(null, current);
            }
            String nextKeywords = page.getMetaKeywords();
            if (!notBlank(nextKeywords)) {
                nextKeywords = buildKeywords(null, nextTitle, current);
            }
            if (Objects.equals(page.getPageTitle(), nextTitle)
                    && Objects.equals(page.getMetaDescription(), nextDescription)
                    && Objects.equals(page.getMetaKeywords(), nextKeywords)) {
                continue;
            }
            page.setPageTitle(nextTitle);
            page.setMetaDescription(nextDescription);
            page.setMetaKeywords(nextKeywords);
            page.setContentHash(contentHash(page));
            page.setUpdateBy(safeOperator(operator));
            page.setUpdateTime(now);
            pageMapper.updateById(page);
            enqueue(page, now);
        }
    }

    private void enqueue(AidSeoPage page, Date now) {
        AidSeoSubmission submission = getOrCreateSubmission(page.getId(), CHANNEL_API, now);
        if (Objects.equals(submission.getSubmittedHash(), page.getContentHash())
                && STATUS_ACCEPTED.equals(submission.getSubmitStatus())) {
            return;
        }
        submission.setSubmitStatus(STATUS_PENDING);
        submission.setNextRetryTime(now);
        submission.setLastErrorCode(null);
        submission.setLastErrorMessage(null);
        submission.setUpdateTime(now);
        submissionMapper.updateById(submission);
    }

    private AidSeoSubmission getOrCreateSubmission(Long pageId, String channel, Date now) {
        AidSeoSubmission existing = submissionMapper.selectOne(new LambdaQueryWrapper<AidSeoSubmission>()
                .eq(AidSeoSubmission::getPageId, pageId)
                .eq(AidSeoSubmission::getProvider, PROVIDER_BAIDU)
                .eq(AidSeoSubmission::getChannel, channel)
                .eq(AidSeoSubmission::getDelFlag, NORMAL)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        AidSeoSubmission created = new AidSeoSubmission();
        created.setPageId(pageId);
        created.setProvider(PROVIDER_BAIDU);
        created.setChannel(channel);
        created.setSubmitStatus(STATUS_PENDING);
        created.setAttemptCount(0);
        created.setNextRetryTime(now);
        created.setDelFlag(NORMAL);
        created.setCreateBy("system");
        created.setCreateTime(now);
        created.setUpdateBy("system");
        created.setUpdateTime(now);
        try {
            submissionMapper.insert(created);
            return created;
        } catch (DuplicateKeyException ignored) {
            return submissionMapper.selectOne(new LambdaQueryWrapper<AidSeoSubmission>()
                    .eq(AidSeoSubmission::getPageId, pageId)
                    .eq(AidSeoSubmission::getProvider, PROVIDER_BAIDU)
                    .eq(AidSeoSubmission::getChannel, channel)
                    .eq(AidSeoSubmission::getDelFlag, NORMAL)
                    .last("LIMIT 1"));
        }
    }

    private void updateSubmission(AidSeoSubmission submission, String status, String submittedHash,
                                  Date nextRetry, Date lastAttempt, Date acceptedAt, Integer httpStatus,
                                  String errorCode, String errorMessage, Integer remain) {
        submission.setSubmitStatus(status);
        submission.setSubmittedHash(submittedHash);
        submission.setNextRetryTime(nextRetry);
        submission.setLastAttemptTime(lastAttempt);
        if (acceptedAt != null) {
            submission.setAcceptedTime(acceptedAt);
        }
        submission.setLastHttpStatus(httpStatus);
        submission.setLastErrorCode(errorCode);
        submission.setLastErrorMessage(safeText(errorMessage, 500));
        submission.setProviderRemain(remain);
        submission.setAttemptCount((submission.getAttemptCount() == null ? 0 : submission.getAttemptCount()) + 1);
        submission.setUpdateBy("system");
        submission.setUpdateTime(new Date());
        submissionMapper.updateById(submission);
    }

    private void saveLog(String batchNo, AidSeoPage page, String channel, String triggerType,
                         String status, Integer httpStatus, String summary, String errorCode,
                         String errorMessage, Long operatorId, String operatorName) {
        AidSeoSubmissionLog row = new AidSeoSubmissionLog();
        row.setBatchNo(batchNo);
        row.setPageId(page.getId());
        row.setProvider(PROVIDER_BAIDU);
        row.setChannel(channel);
        row.setTriggerType(safeText(triggerType, 24));
        row.setSubmitStatus(status);
        row.setUrlSnapshot(page.getCanonicalUrl());
        row.setHttpStatus(httpStatus);
        row.setResponseSummary(safeText(summary, 1000));
        row.setErrorCode(safeText(errorCode, 64));
        row.setErrorMessage(safeText(errorMessage, 500));
        row.setOperatorId(operatorId);
        row.setOperatorName(safeText(operatorName, 64));
        row.setDelFlag(NORMAL);
        row.setCreateBy(safeOperator(operatorName));
        row.setCreateTime(new Date());
        logMapper.insert(row);
    }

    private Map<Long, Map<String, AidSeoSubmission>> loadSubmissions(Collection<Long> pageIds) {
        if (pageIds == null || pageIds.isEmpty()) {
            return Map.of();
        }
        List<AidSeoSubmission> rows = submissionMapper.selectList(new LambdaQueryWrapper<AidSeoSubmission>()
                .in(AidSeoSubmission::getPageId, pageIds)
                .eq(AidSeoSubmission::getProvider, PROVIDER_BAIDU)
                .eq(AidSeoSubmission::getDelFlag, NORMAL));
        Map<Long, Map<String, AidSeoSubmission>> result = new HashMap<>();
        for (AidSeoSubmission row : rows) {
            result.computeIfAbsent(row.getPageId(), ignored -> new HashMap<>()).put(row.getChannel(), row);
        }
        return result;
    }

    private SeoModels.PageView toPageView(AidSeoPage page, Map<String, AidSeoSubmission> submissions) {
        Map<String, AidSeoSubmission> safe = submissions == null ? Map.of() : submissions;
        AidSeoSubmission api = safe.get(CHANNEL_API);
        AidSeoSubmission manual = safe.get(CHANNEL_MANUAL);
        SeoModels.PageView result = new SeoModels.PageView();
        result.setId(page.getId());
        result.setSourceType(page.getSourceType());
        result.setSourceId(page.getSourceId());
        result.setPagePath(page.getPagePath());
        result.setCanonicalUrl(page.getCanonicalUrl());
        result.setPageTitle(page.getPageTitle());
        result.setMetaDescription(page.getMetaDescription());
        result.setMetaKeywords(page.getMetaKeywords());
        result.setOgImageUrl(page.getOgImageUrl());
        result.setIndexable(Objects.equals(page.getIndexable(), 1));
        result.setSitemapEnabled(Objects.equals(page.getSitemapEnabled(), 1));
        result.setStatus(page.getStatus());
        result.setApiStatus(api == null ? null : api.getSubmitStatus());
        result.setManualStatus(manual == null ? null : manual.getSubmitStatus());
        AidSeoSubmission latest = latest(api, manual);
        if (latest != null) {
            result.setAttemptCount(latest.getAttemptCount());
            result.setLastAttemptTime(latest.getLastAttemptTime());
            result.setAcceptedTime(latest.getAcceptedTime());
            result.setNextRetryTime(latest.getNextRetryTime());
            result.setLastErrorMessage(latest.getLastErrorMessage());
        }
        result.setUpdateTime(page.getUpdateTime());
        return result;
    }

    private SeoModels.LogView toLogView(AidSeoSubmissionLog row) {
        SeoModels.LogView result = new SeoModels.LogView();
        result.setId(row.getId());
        result.setBatchNo(row.getBatchNo());
        result.setPageId(row.getPageId());
        result.setProvider(row.getProvider());
        result.setChannel(row.getChannel());
        result.setTriggerType(row.getTriggerType());
        result.setSubmitStatus(row.getSubmitStatus());
        result.setUrlSnapshot(row.getUrlSnapshot());
        result.setHttpStatus(row.getHttpStatus());
        result.setResponseSummary(row.getResponseSummary());
        result.setErrorCode(row.getErrorCode());
        result.setErrorMessage(row.getErrorMessage());
        result.setOperatorName(row.getOperatorName());
        result.setCreateTime(row.getCreateTime());
        return result;
    }

    private long countPages(Boolean indexable) {
        return pageMapper.selectCount(new LambdaQueryWrapper<AidSeoPage>()
                .eq(AidSeoPage::getDelFlag, NORMAL)
                .eq(indexable != null, AidSeoPage::getIndexable, Boolean.TRUE.equals(indexable) ? 1 : 0));
    }

    private long countSubmissions(Set<String> statuses) {
        return submissionMapper.selectCount(new LambdaQueryWrapper<AidSeoSubmission>()
                .eq(AidSeoSubmission::getProvider, PROVIDER_BAIDU)
                .eq(AidSeoSubmission::getChannel, CHANNEL_API)
                .in(AidSeoSubmission::getSubmitStatus, statuses)
                .eq(AidSeoSubmission::getDelFlag, NORMAL));
    }

    private Set<Long> submissionPageIds(String status) {
        LambdaQueryWrapper<AidSeoSubmission> query = new LambdaQueryWrapper<AidSeoSubmission>()
                .eq(AidSeoSubmission::getProvider, PROVIDER_BAIDU)
                .eq(AidSeoSubmission::getDelFlag, NORMAL);
        if (notBlank(status)) {
            query.eq(AidSeoSubmission::getSubmitStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        return submissionMapper.selectList(query).stream()
                .map(AidSeoSubmission::getPageId).collect(Collectors.toSet());
    }

    private AidSeoPage requirePage(Long pageId) {
        AidSeoPage page = pageId == null ? null : pageMapper.selectById(pageId);
        if (page == null || !NORMAL.equals(page.getDelFlag())) {
            throw new ServiceException("SEO 页面不存在");
        }
        return page;
    }

    private Map<String, String> configValues(String category) {
        try {
            Map<String, String> values = configService.getConfigValues(category);
            return values == null ? Map.of() : values;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void upsertConfig(String name, String value) {
        aidConfigService.upsertConfigValue(CONFIG_CATEGORY, name, value == null ? "" : value);
    }

    private String buildRobots(SeoModels.Settings settings) {
        StringBuilder builder = new StringBuilder("User-agent: *\nAllow: /\n");
        for (String rule : normalizeRobotsRules(settings.getRobotsDisallow()).split("\n")) {
            if (!rule.isBlank()) {
                builder.append("Disallow: ").append(rule).append('\n');
            }
        }
        if (notBlank(settings.getSitemapUrl())) {
            builder.append("Sitemap: ").append(settings.getSitemapUrl()).append('\n');
        }
        return builder.toString();
    }

    private String normalizeRobotsRules(String value) {
        LinkedHashSet<String> rules = new LinkedHashSet<>();
        if (value != null) {
            for (String line : value.replace('\r', '\n').split("\n")) {
                String rule = line.trim();
                if (rule.isEmpty() || rule.startsWith("#")) {
                    continue;
                }
                if (!rule.startsWith("/")) {
                    rule = "/" + rule;
                }
                if (rule.length() <= 200 && !rule.contains("\n")) {
                    rules.add(rule);
                }
            }
        }
        return String.join("\n", rules);
    }

    private String normalizeSiteUrl(String value, boolean required) {
        if (!notBlank(value)) {
            if (required) {
                throw new ServiceException("网站地址不能为空");
            }
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !notBlank(uri.getHost()) || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            String path = uri.getPath();
            if (notBlank(path) && !"/".equals(path)) {
                throw new IllegalArgumentException();
            }
            int port = uri.getPort();
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                    + uri.getHost().toLowerCase(Locale.ROOT) + (port > 0 ? ":" + port : "");
        } catch (Exception ex) {
            if (required) {
                throw new ServiceException("网站地址必须是仅包含协议和域名的完整 URL");
            }
            return null;
        }
    }

    private String normalizePagePath(String value, String siteUrl) {
        if (!notBlank(value)) {
            throw new ServiceException("页面路径不能为空");
        }
        String path = value.trim();
        if (path.startsWith("http://") || path.startsWith("https://")) {
            assertSameOrigin(siteUrl, path, "页面 URL 必须属于当前网站");
            URI uri = URI.create(path);
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new ServiceException("页面 URL 不能包含查询参数或锚点");
            }
            path = firstNotBlank(uri.getRawPath(), "/");
        }
        if (!path.startsWith("/") || path.contains("?") || path.contains("#") || path.contains("\\")
                || path.contains("..")) {
            throw new ServiceException("页面路径必须以 / 开头，且不能包含查询参数、锚点或路径穿越");
        }
        path = path.replaceAll("/{2,}", "/");
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.length() > 500) {
            throw new ServiceException("页面路径不能超过 500 个字符");
        }
        return path;
    }

    private void assertSameOrigin(String expected, String actual, String message) {
        try {
            URI left = URI.create(expected);
            URI right = URI.create(actual);
            int leftPort = normalizedPort(left);
            int rightPort = normalizedPort(right);
            if (!left.getScheme().equalsIgnoreCase(right.getScheme())
                    || !left.getHost().equalsIgnoreCase(right.getHost()) || leftPort != rightPort) {
                throw new ServiceException(message);
            }
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServiceException(message);
        }
    }

    private int normalizedPort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private String buildTitle(String input, String path, SeoModels.Settings settings) {
        String base = firstNotBlank(trim(input, 120), "/".equals(path) ? settings.getSiteName() : pathName(path), settings.getSiteName());
        String suffix = trim(settings.getTitleSuffix(), 80);
        if (notBlank(suffix) && !base.equals(suffix) && !base.endsWith(" - " + suffix)) {
            base = base + " - " + suffix;
        }
        return trim(base, 120);
    }

    private String stripTitleSuffix(String title, String suffix) {
        if (!notBlank(title) || !notBlank(suffix)) {
            return title;
        }
        String marker = " - " + suffix.trim();
        return title.endsWith(marker) ? title.substring(0, title.length() - marker.length()) : title;
    }

    private String buildDescription(String input, SeoModels.Settings settings) {
        return trim(firstNotBlank(input, settings.getDefaultDescription(), settings.getSiteName()), 300);
    }

    private String buildKeywords(String input, String title, SeoModels.Settings settings) {
        LinkedHashSet<String> words = new LinkedHashSet<>();
        addKeywords(words, input);
        addKeywords(words, title == null ? null : title.replace(" - ", ","));
        addKeywords(words, settings.getDefaultKeywords());
        return words.stream().limit(10).collect(Collectors.joining(","));
    }

    private void addKeywords(Set<String> target, String value) {
        if (!notBlank(value)) {
            return;
        }
        for (String item : value.split("[\\s,，、;；|/]+")) {
            String word = trim(item, 32);
            if (notBlank(word)) {
                target.add(word);
            }
        }
    }

    private String contentHash(AidSeoPage page) {
        String source = String.join("\u001f",
                nullToEmpty(page.getCanonicalUrl()), nullToEmpty(page.getPageTitle()),
                nullToEmpty(page.getMetaDescription()), nullToEmpty(page.getMetaKeywords()),
                nullToEmpty(page.getOgImageUrl()), String.valueOf(page.getIndexable()),
                String.valueOf(page.getSitemapEnabled()), nullToEmpty(page.getStatus()),
                page.getSourceUpdateTime() == null ? "" : String.valueOf(page.getSourceUpdateTime().getTime()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SEO 页面摘要生成失败", ex);
        }
    }

    private Set<String> jsonArraySet(JsonNode node) {
        Set<String> result = new HashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            });
        }
        return result;
    }

    private List<Long> cleanIds(List<Long> ids, int max) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).filter(id -> id > 0).distinct().limit(max).toList();
    }

    private Date nextRetry(AidSeoSubmission submission, Date now) {
        int attempts = submission.getAttemptCount() == null ? 0 : submission.getAttemptCount();
        long hours = attempts < 1 ? 1 : attempts < 3 ? 6 : 24;
        return Date.from(now.toInstant().plus(hours, ChronoUnit.HOURS));
    }

    private Date tomorrow(Date now) {
        return Date.from(now.toInstant().plus(1, ChronoUnit.DAYS));
    }

    private AidSeoSubmission latest(AidSeoSubmission left, AidSeoSubmission right) {
        if (left == null) return right;
        if (right == null) return left;
        Date leftTime = left.getLastAttemptTime();
        Date rightTime = right.getLastAttemptTime();
        if (leftTime == null) return right;
        if (rightTime == null) return left;
        return leftTime.after(rightTime) ? left : right;
    }

    private boolean isEligible(AidSeoPage page) {
        return page != null && Objects.equals(page.getIndexable(), 1)
                && ENABLED.equals(page.getStatus()) && NORMAL.equals(page.getDelFlag())
                && notBlank(page.getCanonicalUrl());
    }

    private Date epochDate(String value) {
        try {
            return notBlank(value) ? new Date(Long.parseLong(value)) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int intValue(String value, int fallback, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean booleanValue(String value, boolean fallback) {
        return notBlank(value) ? Boolean.parseBoolean(value.trim()) : fallback;
    }

    private boolean isEncryptedSecret(String value) {
        return notBlank(value) && value.startsWith("enc:v1:");
    }

    private String batchNo(String prefix) {
        return prefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String joinUrl(String base, String path) {
        if (!notBlank(base)) {
            return null;
        }
        String normalizedPath = notBlank(path) ? path.trim() : "/";
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return base.replaceAll("/+$", "") + normalizedPath;
    }

    private String pathName(String path) {
        int index = path.lastIndexOf('/');
        String name = index >= 0 ? path.substring(index + 1) : path;
        return name.isBlank() ? "页面" : name.replace('-', ' ').replace('_', ' ');
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.length() <= maxLength ? result : result.substring(0, maxLength);
    }

    private String safeText(String value, int maxLength) {
        return trim(value, maxLength);
    }

    private String sanitizeSecret(String value, String secret) {
        if (!notBlank(value)) {
            return value;
        }
        String sanitized = value;
        if (notBlank(secret)) {
            sanitized = sanitized.replace(secret, "[REDACTED]");
            sanitized = sanitized.replace(encode(secret), "[REDACTED]");
        }
        return safeText(sanitized, 500);
    }

    /** 百度普通收录的 site 参数使用资源平台验证的站点主机名，不包含协议或路径。 */
    private String baiduSiteParameter(String siteUrl) {
        try {
            URI uri = URI.create(normalizeSiteUrl(siteUrl, true));
            String host = uri.getHost();
            if (!notBlank(host)) {
                throw new IllegalArgumentException("Missing host");
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (Exception ex) {
            throw new ServiceException("百度站点地址无法转换为资源平台站点参数");
        }
    }

    private String safeOperator(String value) {
        return firstNotBlank(trim(value, 64), "system");
    }

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
