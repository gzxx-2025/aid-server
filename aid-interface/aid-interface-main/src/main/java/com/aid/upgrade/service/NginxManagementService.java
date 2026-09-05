package com.aid.upgrade.service;

import com.aid.common.exception.ServiceException;
import com.aid.upgrade.client.UpdaterClient;
import com.aid.upgrade.dto.DeploymentConfigVo;
import com.aid.upgrade.dto.NginxConfigRequest;
import com.aid.upgrade.dto.UpdaterStatusVo;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 通过独立升级器管理Nginx，不授予应用进程系统命令执行权限。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NginxManagementService {
    private final UpdaterClient updaterClient;

    public DeploymentConfigVo configuration() {
        UpdaterStatusVo status = updaterClient.detect();
        if (Objects.isNull(status.getDeploymentConfig())) {
            throw failure("升级器配置未就绪");
        }
        return status.getDeploymentConfig();
    }

    public String submit(String action, NginxConfigRequest request) {
        if (!Objects.equals(action, "NGINX_VALIDATE") && !Objects.equals(action, "NGINX_APPLY")
                && !Objects.equals(action, "NGINX_ROLLBACK")) {
            throw failure("不支持此操作");
        }
        UpdaterStatusVo status = updaterClient.detect();
        DeploymentConfigVo config = status.getDeploymentConfig();
        if (!status.isReady() || Objects.isNull(config) || Objects.isNull(config.getValues())
                || !Objects.equals("true", config.getValues().get("NGINX_MANAGEMENT_AVAILABLE"))) {
            throw failure("请先升级部署组件");
        }
        if (!Objects.equals(request.getExpectedRevision(), config.getValues().get("NGINX_REVISION"))) {
            throw failure("配置已变请刷新");
        }
        JSONObject values = new JSONObject();
        if (!Objects.equals(action, "NGINX_ROLLBACK")) {
            requireValue(values, "NGINX_BACKEND_ORIGIN", request.getBackendOrigin());
            requireValue(values, "NGINX_MAX_BODY_MB", request.getMaxBodyMb());
            requireValue(values, "NGINX_READ_TIMEOUT_SECONDS", request.getReadTimeoutSeconds());
            requireValue(values, "NGINX_CONNECT_TIMEOUT_SECONDS", request.getConnectTimeoutSeconds());
            String extra = Objects.toString(request.getExtraDirectives(), "").trim();
            if (extra.contains("\n") || extra.contains("\r") || extra.contains("\0")) {
                throw failure("扩展指令格式错误");
            }
            values.put("NGINX_EXTRA_DIRECTIVES", extra);
        }
        JSONObject task = new JSONObject();
        task.put("schemaVersion", 1);
        task.put("taskId", UUID.randomUUID().toString());
        task.put("action", action);
        task.put("requestedAt", Instant.now().toString());
        task.put("nginxRevision", request.getExpectedRevision());
        task.put("configValues", values);
        updaterClient.submitTask(task);
        log.info("已受理Nginx配置任务, action={}, taskId={}", action, task.getString("taskId"));
        return task.getString("taskId");
    }

    private void requireValue(Map<String, Object> values, String key, String value) {
        if (Objects.isNull(value) || value.isBlank() || value.contains("\n") || value.contains("\r") || value.contains("\0")) {
            throw failure("配置参数不完整");
        }
        values.put(key, value.trim());
    }

    private ServiceException failure(String message) {
        log.info("Nginx配置请求拒绝: {}", message);
        return new ServiceException(message);
    }
}
