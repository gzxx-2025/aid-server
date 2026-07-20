package com.aid.aid.moderation;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.aid.aid.domain.AidImageModerationLog;
import com.aid.aid.service.IAidImageModerationLogService;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.core.domain.entity.SysRole;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.exception.ServiceException;
import com.aid.common.moderation.ImageModerationClient;
import com.aid.common.moderation.ModerationDecider;
import com.aid.common.moderation.ModerationDecision;
import com.aid.common.moderation.ModerationRequest;
import com.aid.common.moderation.ModerationResult;
import com.aid.common.moderation.config.ImageModerationConfigManager;
import com.aid.common.moderation.fetch.ImageBytesFetcher;
import com.aid.common.moderation.properties.ImageModerationProperties;
import com.aid.common.utils.SecurityUtils;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户图片上传内容安全审查接入守卫
 * - 业务侧单行调用：上传后审（传 fileUrl）或上传前审（传字节）
 * - 命中拦截则删除已上传对象并抛出业务异常；异常按 failOpenOnError 决定放行或拒绝
 * - 仅在 BLOCK/REVIEW/ERROR 写审查日志（PASS 由配置 logPassed 控制）
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserImageUploadModerationGuard
{
    /**
     * 删除已上传对象的最大重试次数
     */
    private static final int MAX_DELETE_RETRY = 3;

    /**
     * C 端 App 用户默认角色 ID（静默注册分配，见 {@code SilentRegistrationUtils.DEFAULT_ROLE_ID}）。
     * 仅持有该角色视为 C 端普通用户；持有其它（管理）角色或为超管则视为后台管理端。
     */
    private static final Long C_SIDE_DEFAULT_ROLE_ID = 2L;

    /**
     * 允许审查的图片扩展名白名单（小写）
     */
    private static final String[] IMAGE_EXTENSIONS = {"jpg", "jpeg", "png", "webp", "gif", "bmp"};

    /**
     * 命中拦截时的提示文案
     */
    private static final String MSG_BLOCK = "图片违规，已拦截";

    /**
     * 审查服务不可用时的提示文案
     */
    private static final String MSG_SERVICE_UNAVAILABLE = "内容安全服务暂不可用，请稍后再试";

    /**
     * 图片审查客户端
     */
    private final ImageModerationClient imageModerationClient;

    /**
     * 图片审查配置管理器
     */
    private final ImageModerationConfigManager imageModerationConfigManager;

    /**
     * 图片字节获取器
     */
    private final ImageBytesFetcher imageBytesFetcher;

    /**
     * 审查日志 Service
     */
    private final IAidImageModerationLogService aidImageModerationLogService;

    /**
     * OSS 操作模板（用于删除违规对象）
     */
    private final OssTemplate ossTemplate;

    /**
     * 上传后审：传入已上传的图片 URL，命中拦截则删除对象并抛业务异常
     *
     * @param fileUrl   已上传图片 URL
     * @param bizSource 业务来源
     * @param userId    上传用户 ID
     */
    public void checkUploadedOrThrow(String fileUrl, String bizSource, Long userId)
    {
        // 后台管理端上传不做内容审查，仅 C 端 App 用户需要审查
        if (isBackendManagementCall())
        {
            return;
        }
        ImageModerationProperties props = imageModerationConfigManager.getProperties();
        // 审查关闭或客户端不可用，直接放行
        if (!isModerationActive(props))
        {
            return;
        }
        // 非图片（按扩展名兜底）直接放行
        if (!isImageFile(fileUrl))
        {
            return;
        }

        long start = System.currentTimeMillis();
        ModerationResult result;
        try
        {
            // 按 uploadMode 决定传 URL 还是字节
            ImageBytesFetcher.FetchOutcome outcome = imageBytesFetcher.resolve(fileUrl);
            ModerationRequest req = new ModerationRequest();
            req.setFileName(extractFileName(fileUrl));
            req.setBizSource(bizSource);
            req.setUserId(userId);
            if (outcome.isUseUrl())
            {
                req.setFileUrl(outcome.getUrl());
            }
            else
            {
                req.setFileContent(outcome.getBytes());
            }
            result = imageModerationClient.moderate(req);
        }
        catch (RuntimeException e)
        {
            // 审查异常：按 failOpenOnError 决定放行或拒绝
            handleModerationError(props, fileUrl, bizSource, userId, start, e);
            return;
        }

        // 计算处置决策并执行
        applyDecision(props, result, fileUrl, bizSource, userId, start, true);
    }

    /**
     * 上传前审：传入图片字节，命中拦截直接抛业务异常（此时尚未上传，无需删对象）
     *
     * @param bytes     图片字节
     * @param fileName  文件名
     * @param bizSource 业务来源
     * @param userId    上传用户 ID
     */
    public void checkBytesOrThrow(byte[] bytes, String fileName, String bizSource, Long userId)
    {
        // 后台管理端上传不做内容审查，仅 C 端 App 用户需要审查
        if (isBackendManagementCall())
        {
            return;
        }
        ImageModerationProperties props = imageModerationConfigManager.getProperties();
        // 审查关闭或客户端不可用，直接放行
        if (!isModerationActive(props))
        {
            return;
        }
        // 非图片（按扩展名兜底）直接放行
        if (!isImageFile(fileName))
        {
            return;
        }
        // 字节为空无法审查，放行交由后续上传逻辑处理
        if (Objects.isNull(bytes) || bytes.length == 0)
        {
            return;
        }

        long start = System.currentTimeMillis();
        ModerationResult result;
        try
        {
            ModerationRequest req = new ModerationRequest();
            req.setFileContent(bytes);
            req.setFileName(fileName);
            req.setBizSource(bizSource);
            req.setUserId(userId);
            result = imageModerationClient.moderate(req);
        }
        catch (RuntimeException e)
        {
            // 审查异常：按 failOpenOnError 决定放行或拒绝（上传前无对象可删）
            handleModerationError(props, null, bizSource, userId, start, e);
            return;
        }

        // 计算处置决策并执行（上传前审，不删对象）
        applyDecision(props, result, null, bizSource, userId, start, false);
    }

    /**
     * 是否后台管理端调用：后台管理（超管及各管理角色）上传图片不做内容审查，仅 C 端 App 用户需要审查。
     * 判定规则：超管（role_id=1）或持有任一非「C 端默认角色（role_id=2）」的角色，即视为后台管理用户。
     * 取不到登录上下文（匿名 / 解析异常）一律按「非后台」处理，照常审查，保证安全默认（宁可多审不漏审）。
     *
     * @return true=后台管理端调用，跳过审查；false=按 C 端处理，照常审查
     */
    private boolean isBackendManagementCall()
    {
        try
        {
            LoginUser loginUser = SecurityUtils.getLoginUser();
            if (Objects.isNull(loginUser) || Objects.isNull(loginUser.getUser()))
            {
                return false;
            }
            SysUser user = loginUser.getUser();
            // 超级管理员直接视为后台
            if (user.isAdmin())
            {
                return true;
            }
            List<SysRole> roles = user.getRoles();
            if (Objects.isNull(roles) || roles.isEmpty())
            {
                // 角色未加载（典型为 C 端登录态）→ 按 C 端处理，照常审查
                return false;
            }
            // 持有任一非 C 端默认角色的角色 → 视为后台管理用户
            for (SysRole role : roles)
            {
                if (Objects.nonNull(role) && Objects.nonNull(role.getRoleId())
                        && !C_SIDE_DEFAULT_ROLE_ID.equals(role.getRoleId()))
                {
                    return true;
                }
            }
            return false;
        }
        catch (Exception e)
        {
            // 无登录上下文 / 解析异常：按非后台处理，照常审查（安全默认）
            return false;
        }
    }

    /**
     * 应用处置决策。
     *
     * @param props        配置
     * @param result       审查结果
     * @param fileUrl      图片 URL（上传前审为 null）
     * @param bizSource    业务来源
     * @param userId       用户 ID
     * @param start        审查开始时间戳
     * @param afterUpload  是否上传后审（决定是否删对象）
     */
    private void applyDecision(ImageModerationProperties props, ModerationResult result, String fileUrl,
            String bizSource, Long userId, long start, boolean afterUpload)
    {
        ModerationDecision decision = ModerationDecider.decide(result, props);
        long elapsed = System.currentTimeMillis() - start;

        if (decision == ModerationDecision.BLOCK)
        {
            // 命中拦截：上传后审需删除违规对象
            if (afterUpload)
            {
                asyncDeleteWithRetry(fileUrl);
            }
            // 记录拦截日志
            recordLog(result, ModerationDecision.BLOCK.name(), fileUrl, bizSource, userId, elapsed, null);
            // 抛出业务异常阻断上传
            log.error("图片审查命中拦截, bizSource={}, userId={}, label={}", bizSource, userId,
                    Objects.isNull(result) ? null : result.getLabel());
            throw new ServiceException(MSG_BLOCK);
        }

        if (decision == ModerationDecision.REVIEW)
        {
            // 复审：放行但记日志，不删不抛
            recordLog(result, ModerationDecision.REVIEW.name(), fileUrl, bizSource, userId, elapsed, null);
            return;
        }

        // PASS：放行，是否记日志由配置 logPassed 控制（Service 内部判断）
        recordLog(result, ModerationDecision.PASS.name(), fileUrl, bizSource, userId, elapsed, null);
    }

    /**
     * 处理审查异常：区分「图片本身格式问题」与「系统/网络异常」——。
     *
     * @param props     配置
     * @param fileUrl   图片 URL（可空）
     * @param bizSource 业务来源
     * @param userId    用户 ID
     * @param start     审查开始时间戳
     * @param e         异常
     */
    private void handleModerationError(ImageModerationProperties props, String fileUrl, String bizSource,
            Long userId, long start, Exception e)
    {
        long elapsed = System.currentTimeMillis() - start;
        // 异常前打日志便于排查
        log.error("图片审查异常, bizSource={}, userId={}, error={}", bizSource, userId, e.getMessage(), e);

        String errorMsg = e.getMessage();
        // 「图片格式异常」「图片下载失败」「图片过大」等图片本身的问题：不走 fail-open/close，直接给用户明确提示
        boolean isImageFault = StrUtil.equalsAnyIgnoreCase(errorMsg,
                "图片格式异常", "图片下载失败", "图片内容为空", "图片过大", "缺少图片来源");

        if (isImageFault)
        {
            // 记录为 BLOCK 而不是 ERROR：图片不合规被拦下，含义更准确
            recordLog(null, ModerationDecision.BLOCK.name(), fileUrl, bizSource, userId, elapsed, errorMsg);
            throw new ServiceException(errorMsg);
        }

        // 真正的系统/鉴权/网络异常：记 ERROR 日志
        recordLog(null, ModerationDecision.ERROR.name(), fileUrl, bizSource, userId, elapsed, errorMsg);

        boolean failOpen = Objects.nonNull(props) && props.isFailOpenOnError();
        if (failOpen)
        {
            // fail-open：放行，避免拖垮主站
            return;
        }
        // fail-close（默认）：审查服务异常时拒绝上传
        throw new ServiceException(MSG_SERVICE_UNAVAILABLE);
    }

    /**
     * 组装并提交一条审查日志。
     *
     * @param result       审查结果（可空）
     * @param status       审查状态
     * @param fileUrl      图片 URL
     * @param bizSource    业务来源
     * @param userId       用户 ID
     * @param elapsed      耗时（毫秒）
     * @param errorMessage 错误信息（ERROR 时填）
     */
    private void recordLog(ModerationResult result, String status, String fileUrl, String bizSource,
            Long userId, long elapsed, String errorMessage)
    {
        try
        {
            AidImageModerationLog logEntity = new AidImageModerationLog();
            logEntity.setUserId(userId);
            logEntity.setBizSource(bizSource);
            logEntity.setFileUrl(fileUrl);
            logEntity.setStatus(status);
            logEntity.setElapsedMs(elapsed);
            logEntity.setErrorMessage(truncate(errorMessage));
            if (Objects.nonNull(result))
            {
                logEntity.setSuggestion(result.getSuggestion());
                logEntity.setLabel(result.getLabel());
                logEntity.setSubLabel(result.getSubLabel());
                logEntity.setScore(result.getScore());
                logEntity.setRequestId(result.getRequestId());
                logEntity.setFileMd5(result.getFileMd5());
            }
            aidImageModerationLogService.record(logEntity);
        }
        catch (Exception ex)
        {
            // 记日志失败不影响主流程
            log.error("提交图片审查日志失败, status={}, userId={}, error={}", status, userId, ex.getMessage(), ex);
        }
    }

    /**
     * 异步删除违规对象，最多重试 3 次，失败仅记日志
     *
     * @param fileUrl 图片 URL
     */
    private void asyncDeleteWithRetry(String fileUrl)
    {
        if (StrUtil.isBlank(fileUrl))
        {
            return;
        }
        // 异步执行，避免删除耗时阻塞上传响应
        ThreadUtil.execAsync(() -> {
            for (int i = 1; i <= MAX_DELETE_RETRY; i++)
            {
                try
                {
                    boolean deleted = ossTemplate.deleteByUrl(fileUrl);
                    if (deleted)
                    {
                        return;
                    }
                    log.warn("删除违规图片返回失败, 第{}次, url={}", i, fileUrl);
                }
                catch (Exception e)
                {
                    // 删除异常仅记日志，按重试次数继续
                    log.error("删除违规图片异常, 第{}次, url={}, error={}", i, fileUrl, e.getMessage(), e);
                }
            }
            log.error("删除违规图片最终失败, url={}", fileUrl);
        });
    }

    /**
     * 审查是否处于激活状态（总开关开启且客户端可用）
     *
     * @param props 配置
     * @return true=激活
     */
    private boolean isModerationActive(ImageModerationProperties props)
    {
        if (Objects.isNull(props) || !props.isEnabled())
        {
            return false;
        }
        return imageModerationClient.enabled();
    }

    /**
     * 判断文件是否属于图片审查范围（按扩展名白名单）。
     * 上传入口据此决定是否需要读取文件字节送审，非图片（视频/音频等大文件）无需整包载入内存。
     *
     * @param fileNameOrUrl 文件名或 URL
     * @return true=属于图片审查范围
     */
    public boolean shouldModerate(String fileNameOrUrl)
    {
        return isImageFile(fileNameOrUrl);
    }

    /**
     * 按扩展名判断是否图片文件（兜底跳过非图片）
     *
     * @param fileNameOrUrl 文件名或 URL
     * @return true=图片
     */
    private boolean isImageFile(String fileNameOrUrl)
    {
        if (StrUtil.isBlank(fileNameOrUrl))
        {
            return false;
        }
        String name = fileNameOrUrl;
        // 去除查询参数
        int queryIdx = name.indexOf('?');
        if (queryIdx > 0)
        {
            name = name.substring(0, queryIdx);
        }
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == name.length() - 1)
        {
            return false;
        }
        String ext = name.substring(dotIdx + 1).toLowerCase();
        return StrUtil.equalsAnyIgnoreCase(ext, IMAGE_EXTENSIONS);
    }

    /**
     * 从 URL 中提取文件名
     *
     * @param fileUrl URL
     * @return 文件名
     */
    private String extractFileName(String fileUrl)
    {
        if (StrUtil.isBlank(fileUrl))
        {
            return null;
        }
        String name = fileUrl;
        int queryIdx = name.indexOf('?');
        if (queryIdx > 0)
        {
            name = name.substring(0, queryIdx);
        }
        int slashIdx = name.lastIndexOf('/');
        if (slashIdx >= 0 && slashIdx < name.length() - 1)
        {
            return name.substring(slashIdx + 1);
        }
        return name;
    }

    /**
     * 截断错误信息，避免超出列长度（error_message varchar(500)）
     *
     * @param message 原始信息
     * @return 截断后的信息
     */
    private String truncate(String message)
    {
        if (StrUtil.isBlank(message))
        {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
