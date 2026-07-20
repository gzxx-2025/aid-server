package com.aid.common.captcha.service;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.aid.common.captcha.config.CaptchaProperties;
import com.aid.common.captcha.store.RedisCacheStore;
import com.aid.common.config.AidAppConfig;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.application.TACBuilder;
import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.common.response.ApiResponse;
import cloud.tianai.captcha.generator.common.model.dto.GenerateParam;
import cloud.tianai.captcha.resource.CrudResourceStore;
import cloud.tianai.captcha.resource.ResourceStore;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import cloud.tianai.captcha.validator.common.model.dto.MatchParam;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 行为验证码核心服务。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class CaptchaService {

    /** 支持的验证码类型（与 tianai 内置模板对应） */
    private static final List<String> SUPPORTED_TYPES = Arrays.asList(
            CaptchaTypeConstant.SLIDER,
            CaptchaTypeConstant.ROTATE,
            CaptchaTypeConstant.WORD_IMAGE_CLICK,
            CaptchaTypeConstant.CONCAT);

    /** 本地文件资源 provider 名称（见 FileResourceProvider.NAME） */
    private static final String RESOURCE_TYPE_FILE = "file";

    /** 本地背景图缓存子目录（位于 AidAppConfig.profile 下） */
    private static final String LOCAL_DIR_NAME = "captcha-bg";

    /** 允许的图片扩展名 */
    private static final List<String> IMAGE_EXTS = Arrays.asList(".jpg", ".jpeg", ".png", ".webp");

    /** 下载超时（毫秒） */
    private static final int DOWNLOAD_TIMEOUT_MS = 30000;

    @Resource
    private RedisCacheStore redisCacheStore;

    @Resource
    private CaptchaConfigService captchaConfigService;

    @Resource
    private CaptchaTokenService captchaTokenService;

    /** tianai 验证码应用 */
    private ImageCaptchaApplication application;

    /** 本地背景图是否已就绪（有可用图并已注册） */
    private volatile boolean imagesReady = false;

    /** 已成功加载背景图对应的配置签名（仅在加载成功后更新，用于检测变更） */
    private volatile String loadedSignature = null;

    /** 上次尝试加载（含失败）的时间戳，用于失败后的冷却重试 */
    private volatile long lastAttemptTime = 0L;

    /** 加载失败后的最小重试间隔（毫秒），避免坏地址被高频狂刷 */
    private static final long RETRY_COOLDOWN_MS = 10000L;

    /** 背景图同步锁 */
    private final Object syncLock = new Object();

    /**
     * 启动时装配验证码应用（不依赖背景图，背景图在 ApplicationReadyEvent 后加载）。
     */
    @PostConstruct
    public void init() {
        try {
            // 内置模板（缺口/旋转/点选/还原 + 内置字体）+ Redis 缓存
            this.application = TACBuilder.builder()
                    .addDefaultTemplate()
                    .setCacheStore(redisCacheStore)
                    .prefix(CaptchaProperties.REDIS_CAPTCHA_PREFIX)
                    .build();
            log.info("行为验证码应用初始化完成");
        } catch (Exception e) {
            // 初始化失败先 log，application 置空，后续按未开启处理避免 500
            log.error("行为验证码应用初始化失败", e);
            this.application = null;
        }
    }

    /**
     * 应用就绪后自动加载本地背景图（此时 AidAppConfig.profile 与数据库均已就绪）。
     * 加载失败不影响应用启动，仅置为不开启（后续调用时会按冷却自动重试）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        if (Objects.isNull(application)) {
            return;
        }
        // 启动时强制尝试一次（忽略冷却）
        ensureImages(true);
    }

    /**
     * 是否真正生效：应用就绪 + 配置开启 + 本地背景图就绪。
     *
     * 开启状态下若尚未就绪，会按冷却自动尝试加载（本地优先→配置下载），实现失败自愈；
     * 同时惰性检测配置变更（背景图换了则删本地旧图、重新下载）。
     *
     * @param force true=忽略冷却强制尝试一次（供运维诊断 / 内部 publicConfig 显式触发使用）
     */
    public boolean isOperational(boolean force) {
        if (Objects.isNull(application)) {
            return false;
        }
        // 未开启：不触发任何加载/下载
        if (!captchaConfigService.isEnabled()) {
            return false;
        }
        ensureImages(force);
        return imagesReady;
    }

    /** 默认带冷却的 isOperational。 */
    public boolean isOperational() {
        return isOperational(false);
    }

    /**
     * 确保背景图就绪的统一入口。
     *
     * @param force true=启动时强制尝试（忽略冷却）；false=按冷却节流
     */
    private void ensureImages(boolean force) {
        String sig = captchaConfigService.backgroundSignature();
        // 已就绪且配置签名未变：无需任何 IO
        if (imagesReady && Objects.equals(sig, loadedSignature)) {
            return;
        }
        // 非强制时做失败冷却节流，避免坏地址高频狂刷
        long now = System.currentTimeMillis();
        if (!force && (now - lastAttemptTime) < RETRY_COOLDOWN_MS) {
            return;
        }
        synchronized (syncLock) {
            // 双重检查
            String sig2 = captchaConfigService.backgroundSignature();
            if (imagesReady && Objects.equals(sig2, loadedSignature)) {
                return;
            }
            long now2 = System.currentTimeMillis();
            if (!force && (now2 - lastAttemptTime) < RETRY_COOLDOWN_MS) {
                return;
            }
            lastAttemptTime = now2;
            try {
                File dir = ensureLocalDir();
                boolean configChanged = !Objects.equals(sig2, loadedSignature);

                // 配置变更：清掉本地旧图与已注册资源，强制按新配置重建
                if (configChanged) {
                    FileUtil.clean(dir);
                    clearStoreResources();
                    imagesReady = false;
                    log.info("行为验证码: 检测到背景图配置变更，重建本地缓存");
                }

                List<File> local = listLocalImages(dir);
                if (CollectionUtil.isNotEmpty(local)) {
                    if (registerLocalFiles(local)) {
                        imagesReady = true;
                        loadedSignature = sig2;
                        log.info("行为验证码: 从本地加载背景图 {} 张, dir={}", local.size(), dir.getAbsolutePath());
                    }
                    return;
                }

                List<String> urls = captchaConfigService.backgroundUrls();
                if (CollectionUtil.isEmpty(urls)) {
                    imagesReady = false;
                    // 配置也为空：记为已处理（这是确定态，不需要反复重试）
                    loadedSignature = sig2;
                    log.warn("行为验证码未就绪: 本地无背景图且未配置地址，请添加行为式验证码图片");
                    return;
                }

                log.info("行为验证码: 本地无图，开始从配置下载 {} 个地址到 {}", urls.size(), dir.getAbsolutePath());
                List<File> downloaded = downloadToLocal(urls, dir);
                if (CollectionUtil.isNotEmpty(downloaded) && registerLocalFiles(downloaded)) {
                    imagesReady = true;
                    // 只有成功才记签名
                    loadedSignature = sig2;
                    log.info("行为验证码: 下载背景图 {} 张并缓存到本地", downloaded.size());
                } else {
                    // 失败：不更新 loadedSignature，保持下次按冷却重试（自愈）
                    imagesReady = false;
                    log.error("行为验证码: 背景图下载失败，{} 秒后重试", RETRY_COOLDOWN_MS / 1000);
                }
            } catch (Exception e) {
                imagesReady = false;
                log.error("行为验证码背景图加载异常，{} 秒后重试", RETRY_COOLDOWN_MS / 1000, e);
            }
        }
    }

    /**
     * 当前生效的验证码类型（供前端/状态查询展示，RANDOM 原样返回）。
     */
    public String currentType() {
        try {
            return captchaConfigService.rawType();
        } catch (Exception e) {
            log.error("读取验证码类型失败", e);
            return CaptchaTypeConstant.SLIDER;
        }
    }

    /**
     * 诊断状态：用于状态接口直接告诉前端/运维「为什么没生效」。
     *
     * @return 诊断快照（不抛异常，任何异常一律标记为 init_failed）
     */
    public Diagnostics diagnose() {
        Diagnostics d = new Diagnostics();
        try {
            d.applicationOk = Objects.nonNull(application);
            d.enabled = captchaConfigService.isEnabled();
            d.backgroundUrlCount = captchaConfigService.backgroundUrls().size();
            d.localImageCount = listLocalImages(ensureLocalDir()).size();
            d.imagesReady = imagesReady;
            // 推导 reason
            if (!d.applicationOk) {
                d.reason = "init_failed";
            } else if (!d.enabled) {
                d.reason = "config_disabled";
            } else if (d.imagesReady) {
                d.reason = "ok";
            } else if (d.localImageCount == 0 && d.backgroundUrlCount == 0) {
                d.reason = "no_images_configured";
            } else if (d.localImageCount == 0) {
                d.reason = "images_loading_or_download_failed";
            } else {
                d.reason = "register_failed";
            }
        } catch (Exception e) {
            log.error("行为验证码诊断异常", e);
            d.reason = "diagnose_exception:" + e.getClass().getSimpleName();
        }
        return d;
    }

    /**
     * 验证码状态诊断快照。
     */
    @lombok.Data
    public static class Diagnostics {
        /** tianai 应用是否构建成功 */
        private boolean applicationOk;
        /** 配置是否开启 */
        private boolean enabled;
        /** 已配置的远程地址数 */
        private int backgroundUrlCount;
        /** 本地缓存目录中的图片数 */
        private int localImageCount;
        /** 是否已注册到 tianai 资源（可生成验证码） */
        private boolean imagesReady;
        /** 推导出的「未生效」原因，便于前端/运维定位 */
        private String reason;
    }

    /**
     * 生成验证码数据。
     *
     * @return 生效时返回 ImageCaptchaVO；未开启/未就绪返回 null（控制器返回 enabled=false）
     */
    public ImageCaptchaVO generate() {
        if (!isOperational()) {
            return null;
        }
        String type = captchaConfigService.resolveType(SUPPORTED_TYPES);
        try {
            GenerateParam param = new GenerateParam();
            param.setType(type);
            ApiResponse<ImageCaptchaVO> res = application.generateCaptcha(param);
            if (Objects.isNull(res) || !res.isSuccess() || Objects.isNull(res.getData())) {
                log.error("验证码生成失败: type={}, resp={}", type, res);
                return null;
            }
            return res.getData();
        } catch (Exception e) {
            log.error("验证码生成异常: type={}", type, e);
            return null;
        }
    }

    /**
     * 校验行为轨迹，成功则签发一次性 token。
     *
     * @param id    验证码 ID
     * @param track 行为轨迹
     * @return 校验通过返回 token；否则返回 null
     */
    public String check(String id, ImageCaptchaTrack track) {
        if (Objects.isNull(application)) {
            return null;
        }
        try {
            ApiResponse<?> res = application.matching(id, new MatchParam(track));
            if (Objects.nonNull(res) && res.isSuccess()) {
                return captchaTokenService.issue(captchaConfigService.tokenExpireSeconds());
            }
            log.info("验证码校验未通过: id={}, code={}", id, res == null ? null : res.getCode());
            return null;
        } catch (Exception e) {
            log.error("验证码校验异常: id={}", id, e);
            return null;
        }
    }

    /**
     * 本地缓存目录，不存在则创建。
     */
    private File ensureLocalDir() {
        String profile = AidAppConfig.getProfile();
        // profile 兜底：极端情况下用工作目录，保证不抛 NPE
        String base = StrUtil.isNotBlank(profile) ? profile : System.getProperty("user.dir");
        File dir = new File(base, LOCAL_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 列出本地目录中的图片文件。
     */
    private List<File> listLocalImages(File dir) {
        List<File> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) {
            return result;
        }
        for (File f : files) {
            if (f.isFile() && isImage(f.getName())) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * 是否图片文件（按扩展名）。
     */
    private boolean isImage(String name) {
        String lower = name.toLowerCase();
        return IMAGE_EXTS.stream().anyMatch(lower::endsWith);
    }

    /**
     * 把配置的图片地址加载到本地目录。对项目本地 profile 路径（如 {@code /profile/upload/...}）
     * 直接走磁盘拷贝，避免启动期 HTTP 自调用 + 鉴权的不确定性；其它 http(s) 地址走 hutool 下载。
     * 单张失败跳过、不影响其它图。
     */
    private List<File> downloadToLocal(List<String> urls, File dir) {
        List<File> result = new ArrayList<>();
        int idx = 0;
        for (String url : urls) {
            if (StrUtil.isBlank(url)) {
                continue;
            }
            String trimmed = url.trim();
            File dest = new File(dir, "bg_" + (idx++) + guessExt(trimmed));
            try {
                if (resolveLocal(trimmed, dest)) {
                    // 命中本地 profile：从磁盘直接拷贝
                    if (dest.exists() && dest.length() > 0) {
                        result.add(dest);
                        log.info("行为验证码: 从本地profile直接拷贝 {} -> {}", trimmed, dest.getName());
                        continue;
                    }
                    log.error("行为验证码: 本地profile拷贝为空: url={}", trimmed);
                    continue;
                }
                // 远程地址：hutool 下载，带超时与覆盖写入
                HttpUtil.downloadFile(trimmed, dest, DOWNLOAD_TIMEOUT_MS);
                if (dest.exists() && dest.length() > 0) {
                    result.add(dest);
                    log.info("行为验证码: 远程下载 {} -> {} ({} bytes)", trimmed, dest.getName(), dest.length());
                } else {
                    log.error("行为验证码: 远程下载为空: url={}", trimmed);
                }
            } catch (Exception e) {
                // 单张失败先 log，继续下一张
                log.error("行为验证码: 加载背景图失败: url={}, err={}", trimmed, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 若 URL 指向项目本地 profile（{@code /profile/...} 或 {@code http(s)://host/profile/...}），
     * 则从磁盘直接读取文件拷贝到 dest，并返回 true；否则返回 false 由调用方走 HTTP 下载。
     */
    private boolean resolveLocal(String url, File dest) {
        // 找出 RESOURCE_PREFIX（默认 /profile）的位置
        String prefix = com.aid.common.constant.Constants.RESOURCE_PREFIX;
        int i = url.indexOf(prefix + "/");
        if (i < 0) {
            return false;
        }
        // 取 prefix 之后的相对路径，并解码 %xx
        String relative = url.substring(i + prefix.length());
        try {
            relative = java.net.URLDecoder.decode(relative, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            // 解码失败按原样使用
        }
        String profile = AidAppConfig.getProfile();
        if (StrUtil.isBlank(profile)) {
            return false;
        }
        File src = new File(profile, relative);
        if (!src.exists() || !src.isFile()) {
            log.error("行为验证码: 本地profile文件不存在: src={}", src.getAbsolutePath());
            return false;
        }
        try {
            FileUtil.copy(src, dest, true);
            return true;
        } catch (Exception e) {
            log.error("行为验证码: 本地profile拷贝失败: src={}", src.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * 从 URL 推断扩展名，未知则默认 .jpg。
     */
    private String guessExt(String url) {
        String lower = url.toLowerCase();
        for (String ext : IMAGE_EXTS) {
            int q = lower.indexOf('?');
            String path = q > -1 ? lower.substring(0, q) : lower;
            if (path.endsWith(ext)) {
                return ext;
            }
        }
        return ".jpg";
    }

    /**
     * 将本地图片注册为所有类型的背景资源（先清掉旧背景，保留字体/模板）。
     */
    private boolean registerLocalFiles(List<File> files) {
        CrudResourceStore crud = resolveCrudStore();
        if (Objects.isNull(crud)) {
            log.error("验证码资源存储不支持动态注册");
            return false;
        }
        // 仅清掉背景类型旧资源（不动 font 字体资源，否则点选验证码会失效）
        clearBackgroundResources(crud);
        for (File f : files) {
            for (String type : SUPPORTED_TYPES) {
                // 使用 tianai file 资源（全限定名，避免与 jakarta.annotation.Resource 冲突）
                crud.addResource(type, new cloud.tianai.captcha.resource.common.model.dto.Resource(
                        RESOURCE_TYPE_FILE, f.getAbsolutePath()));
            }
        }
        return true;
    }

    /**
     * 清空已注册的背景资源（仅背景类型，不动字体与模板）。
     */
    private void clearStoreResources() {
        CrudResourceStore crud = resolveCrudStore();
        if (Objects.nonNull(crud)) {
            clearBackgroundResources(crud);
        }
    }

    /**
     * 仅删除 SLIDER/ROTATE/WORD_IMAGE_CLICK/CONCAT 这几种背景图资源，
     * 保留 font 字体资源（点选验证码依赖字体）。
     */
    private void clearBackgroundResources(CrudResourceStore crud) {
        for (String type : SUPPORTED_TYPES) {
            try {
                // tag 传 null 表示该类型下所有标签
                List<cloud.tianai.captcha.resource.common.model.dto.Resource> list =
                        crud.listResourcesByTypeAndTag(type, null);
                if (CollectionUtil.isEmpty(list)) {
                    continue;
                }
                // 复制一份 id 列表再删，避免遍历时并发修改
                List<String> ids = new ArrayList<>();
                for (cloud.tianai.captcha.resource.common.model.dto.Resource r : list) {
                    ids.add(r.getId());
                }
                for (String id : ids) {
                    crud.deleteResource(type, id);
                }
            } catch (Exception e) {
                log.error("清理背景资源失败: type={}", type, e);
            }
        }
    }

    /**
     * 解析出底层可增删的 {@link CrudResourceStore}。
     *
     * @return 底层 CrudResourceStore；找不到返回 null
     */
    private CrudResourceStore resolveCrudStore() {
        ResourceStore store = application.getImageCaptchaResourceManager().getResourceStore();
        // 最多解包若干层，防御性避免装饰器自引用导致死循环
        for (int i = 0; i < 8 && Objects.nonNull(store); i++) {
            if (store instanceof CrudResourceStore) {
                return (CrudResourceStore) store;
            }
            ResourceStore target = store.getTarget();
            if (target == store) {
                break;
            }
            store = target;
        }
        return null;
    }
}
