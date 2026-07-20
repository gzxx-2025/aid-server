package com.aid.upgrade.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.upgrade.constant.UpgradeConfigKeys;
import com.aid.upgrade.dto.UpdaterLastTaskVo;
import com.aid.upgrade.dto.UpdaterStatusVo;
import com.alibaba.fastjson2.JSONObject;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 独立升级器（aid-updater）状态探测与任务投递客户端
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdaterClient {

    /** 未安装 */
    public static final String STATUS_NOT_INSTALLED = "NOT_INSTALLED";

    /** 已安装但未运行 */
    public static final String STATUS_STOPPED = "STOPPED";

    /** 运行正常，可一键升级 */
    public static final String STATUS_AVAILABLE = "AVAILABLE";

    /** 协议版本不兼容 */
    public static final String STATUS_INCOMPATIBLE = "INCOMPATIBLE";

    /** 升级配置缺失（如任务文件路径未配置） */
    public static final String STATUS_MISCONFIGURED = "MISCONFIGURED";

    /** 状态文件异常 */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /** 当前后端支持的升级器协议版本 */
    private static final int SUPPORTED_PROTOCOL_VERSION = 1;

    /** 健康文件体积上限，防止误配大文件被整读 */
    private static final long MAX_HEALTH_FILE_BYTES = 64 * 1024L;

    /** 心跳过期阈值（毫秒）：超过视为升级器已停止，防止残留健康文件误判 */
    private static final long HEARTBEAT_STALE_MS = 60_000L;

    private final ConfigService configService;

    /**
     * 探测升级器当前状态
     *
     * @return 升级器状态（永不为null）
     */
    public UpdaterStatusVo detect() {
        UpdaterStatusVo vo = new UpdaterStatusVo();
        vo.setReady(false);
        String healthPath = readUpgradeConfig(UpgradeConfigKeys.KEY_UPDATER_HEALTH_FILE);
        if (StrUtil.isBlank(healthPath)) {
            vo.setStatus(STATUS_NOT_INSTALLED);
            vo.setMessage("未安装自动升级组件，无法使用页面一键升级，仍可手动升级。");
            return vo;
        }
        File healthFile = new File(healthPath);
        if (!healthFile.exists() || !healthFile.isFile()) {
            vo.setStatus(STATUS_NOT_INSTALLED);
            vo.setMessage("未安装自动升级组件，无法使用页面一键升级，仍可手动升级。");
            return vo;
        }
        if (healthFile.length() > MAX_HEALTH_FILE_BYTES) {
            vo.setStatus(STATUS_UNKNOWN);
            vo.setMessage("升级器状态文件异常，请检查 aid-updater 安装。");
            return vo;
        }
        try {
            String content = FileUtil.readUtf8String(healthFile);
            JSONObject health = JSONObject.parseObject(content);
            String status = health.getString("status");
            Integer protocolVersion = health.getInteger("protocolVersion");
            vo.setVersion(health.getString("version"));
            vo.setLastTask(parseLastTask(health));
            if (Objects.equals("RUNNING", status) && isHeartbeatStale(health)) {
                // 升级器异常退出时健康文件可能残留 RUNNING，按心跳时间判定真实状态
                vo.setStatus(STATUS_STOPPED);
                vo.setMessage("升级器心跳超时，请检查 aid-updater 服务是否存活。");
            } else if (Objects.equals("RUNNING", status) && Objects.equals(protocolVersion, SUPPORTED_PROTOCOL_VERSION)) {
                if (StrUtil.isBlank(readUpgradeConfig(UpgradeConfigKeys.KEY_UPDATER_TASK_FILE))) {
                    vo.setStatus(STATUS_MISCONFIGURED);
                    vo.setMessage("升级器任务文件路径未配置，请在升级源配置中补全后使用一键升级。");
                    return vo;
                }
                vo.setStatus(STATUS_AVAILABLE);
                vo.setReady(true);
                vo.setMessage("升级器运行正常，可使用页面一键升级。");
            } else if (Objects.equals("RUNNING", status)) {
                vo.setStatus(STATUS_INCOMPATIBLE);
                vo.setMessage("升级器协议版本不兼容，请先更新 aid-updater。");
            } else {
                vo.setStatus(STATUS_STOPPED);
                vo.setMessage("升级器已安装但未运行，请启动 aid-updater 服务。");
            }
        } catch (Exception e) {
            // 状态文件损坏不影响系统运行，仅提示用户检查
            log.warn("解析升级器健康文件失败, path={}", healthPath, e);
            vo.setStatus(STATUS_UNKNOWN);
            vo.setMessage("升级器状态文件解析失败，请检查 aid-updater 安装。");
        }
        return vo;
    }

    /**
     * 原子写入升级器任务，避免升级器读取到未写完的JSON。
     *
     * @param task 任务内容
     */
    public void submitTask(JSONObject task) {
        String taskFilePath = readUpgradeConfig(UpgradeConfigKeys.KEY_UPDATER_TASK_FILE);
        if (StrUtil.isBlank(taskFilePath)) {
            log.error("提交升级任务失败, 升级器任务文件未配置");
            throw new ServiceException("任务路径未配置");
        }
        Path temporary = null;
        try {
            Path target = Path.of(taskFilePath).toAbsolutePath().normalize();
            Path parent = target.getParent();
            if (Objects.isNull(parent)) {
                log.error("提交升级任务失败, 任务路径无父目录, path={}", target);
                throw new ServiceException("任务路径错误");
            }
            Files.createDirectories(parent);
            if (Files.exists(target)) {
                log.error("提交升级任务失败, 已有任务待处理, path={}", target);
                throw new ServiceException("已有任务处理中");
            }
            temporary = Files.createTempFile(parent, "upgrade-task-", ".tmp");
            Files.writeString(temporary, task.toJSONString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("提交升级任务失败, path={}", taskFilePath, e);
            throw new ServiceException("任务提交失败");
        } finally {
            // 写入或移动失败时清理残留临时文件，避免污染升级器收件目录
            if (Objects.nonNull(temporary)) {
                FileUtil.del(temporary.toFile());
            }
        }
    }

    /**
     * 判断健康文件心跳是否过期；updatedAt 缺失或非法时视为未过期（兼容旧版升级器）
     */
    private boolean isHeartbeatStale(JSONObject health) {
        String updatedAt = health.getString("updatedAt");
        if (StrUtil.isBlank(updatedAt)) {
            return false;
        }
        try {
            long updatedAtMs = DateUtils.parseDate(updatedAt, "yyyy-MM-dd HH:mm:ss").getTime();
            return System.currentTimeMillis() - updatedAtMs > HEARTBEAT_STALE_MS;
        } catch (Exception e) {
            // 时间格式异常不影响主流程，按未过期处理
            return false;
        }
    }

    /**
     * 解析健康文件中的最近任务结果，缺失或异常时返回null
     */
    private UpdaterLastTaskVo parseLastTask(JSONObject health) {
        JSONObject lastTask = health.getJSONObject("lastTask");
        if (Objects.isNull(lastTask)) {
            return null;
        }
        UpdaterLastTaskVo vo = new UpdaterLastTaskVo();
        vo.setTaskId(lastTask.getString("taskId"));
        vo.setAction(lastTask.getString("action"));
        vo.setState(lastTask.getString("state"));
        vo.setMessage(lastTask.getString("message"));
        vo.setFinishedAt(lastTask.getString("finishedAt"));
        return vo;
    }

    /**
     * 读取升级器配置项
     */
    private String readUpgradeConfig(String key) {
        try {
            Map<String, String> configMap = configService.getConfigValues(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE);
            return StrUtil.trimToNull(configMap.get(key));
        } catch (Exception e) {
            // 分类未初始化视为未配置
            return null;
        }
    }
}
