package com.aid.config.test.tester;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.aid.common.config.test.ConfigConnectivityTester;
import com.aid.common.config.test.ConfigTestRequest;
import com.aid.common.config.test.ConfigTestResult;
import com.aid.common.moderation.ModerationRequest;
import com.aid.common.moderation.ModerationResult;
import com.aid.common.moderation.config.ImageModerationConfigManager;
import com.aid.common.moderation.properties.ImageModerationProperties;
import com.aid.common.moderation.tencent.TencentImageModerationClient;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 图片内容安全审查配置连通性测试（testKey=image-moderation）。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageModerationTester implements ConfigConnectivityTester {

    /** 脱敏串标记：密钥含该串视为「未修改」，测试时回退使用已保存的真实密钥。 */
    private static final String MASK_FLAG = "****";

    /**
     * 腾讯云图片审查客户端（提供临时配置审查重载）。
     */
    private final TencentImageModerationClient tencentImageModerationClient;

    /**
     * 图片审查配置管理器：用于在测试时回退取已保存的真实密钥（页面回显的是脱敏串）。
     */
    private final ImageModerationConfigManager imageModerationConfigManager;

    @Override
    public String testKey() {
        return "image-moderation";
    }

    @Override
    public ConfigTestResult test(ConfigTestRequest request) {
        Map<String, Object> payload = request.getPayload();
        String fileUrl = TesterPayloads.str(payload, "fileUrl");
        try {
            // 无图片来源（通用配置页「测试连接」按钮）：走凭证 + 连通性探测
            if (StrUtil.isBlank(fileUrl)) {
                ImageModerationProperties tmpProps = buildTmpProps(payload);
                if (StrUtil.hasBlank(tmpProps.getTencentSecretId(), tmpProps.getTencentSecretKey())) {
                    return ConfigTestResult.fail("请填写完整密钥");
                }
                TencentImageModerationClient.ConnectivityProbeResult probe =
                        tencentImageModerationClient.probeConnectivity(tmpProps);
                ConfigTestResult result = probe.isOk()
                        ? ConfigTestResult.ok(probe.getMessage(), "tencent")
                        : ConfigTestResult.fail(probe.getMessage());
                result.setDetails(probe.getDetail());
                return result;
            }
            // 有图片 URL：发起一次真实审查并回显结果
            ModerationResult result = moderateForTest(payload, null, null, fileUrl);
            return buildResult(result);
        } catch (Exception e) {
            // 兜底：任何异常转友好失败，明细仅超管可见
            log.error("图片审查连通性测试失败, error={}", e.getMessage(), e);
            ConfigTestResult fail = ConfigTestResult.fail("图片审查配置测试失败");
            fail.setDetails(e.getClass().getSimpleName() + ": " + StrUtil.trimToEmpty(e.getMessage()));
            return fail;
        }
    }

    /**
     * 使用临时配置执行一次审查并返回厂商原始结果（供 multipart 测试控制器调用）。
     *
     * @param payload   临时配置（tencentRegion/tencentSecretId/tencentSecretKey/bizType 等）
     * @param fileBytes 上传图片字节（可空）
     * @param fileName  文件名（可空）
     * @param fileUrl   图片 URL（可空，与 fileBytes 二选一）
     * @return 审查结果（含 rawJson）
     */
    public ModerationResult moderateForTest(Map<String, Object> payload, byte[] fileBytes, String fileName,
            String fileUrl) {
        ImageModerationProperties tmpProps = buildTmpProps(payload);
        // 凭证校验
        if (StrUtil.hasBlank(tmpProps.getTencentSecretId(), tmpProps.getTencentSecretKey())) {
            log.error("图片审查测试缺少腾讯云密钥");
            throw new IllegalArgumentException("请填写完整密钥");
        }

        ModerationRequest req = new ModerationRequest();
        req.setFileName(fileName);
        req.setBizSource("config_test");
        if (fileBytes != null && fileBytes.length > 0) {
            // 优先使用上传字节
            req.setFileContent(fileBytes);
        } else if (StrUtil.isNotBlank(fileUrl)) {
            req.setFileUrl(fileUrl);
        } else {
            log.error("图片审查测试缺少图片来源");
            throw new IllegalArgumentException("请提供图片");
        }

        return tencentImageModerationClient.moderateWith(tmpProps, req);
    }

    /**
     * 从临时配置 payload 构建审查配置属性。
     *
     * @param payload 临时配置
     * @return 配置属性
     */
    private ImageModerationProperties buildTmpProps(Map<String, Object> payload) {
        ImageModerationProperties props = new ImageModerationProperties();
        // 测试场景强制启用，避免被总开关拦截
        props.setEnabled(true);
        props.setProvider(TesterPayloads.str(payload, "provider", "tencent"));
        props.setTencentRegion(TesterPayloads.str(payload, "tencentRegion", "ap-shanghai"));
        props.setTencentSecretId(TesterPayloads.str(payload, "tencentSecretId"));
        // 密钥脱敏回退：页面回显/再次保存后 SecretKey 是脱敏串（含 ****）或为空，
        // 说明用户未重新输入，应回退使用已保存的真实密钥，否则会拿脱敏串去鉴权导致测试必然失败。
        props.setTencentSecretKey(resolveSecretKey(TesterPayloads.str(payload, "tencentSecretKey")));
        return props;
    }

    /**
     * 解析测试用 SecretKey：为空或含脱敏串（****）时回退到已保存的真实密钥。
     *
     * @param submitted 页面提交的 SecretKey（可能是脱敏串）
     * @return 真实可用的 SecretKey（回退失败则原样返回，由上层凭证校验拦截）
     */
    private String resolveSecretKey(String submitted) {
        if (StrUtil.isNotBlank(submitted) && !submitted.contains(MASK_FLAG)) {
            return submitted;
        }
        ImageModerationProperties saved = imageModerationConfigManager.getProperties();
        if (saved != null && StrUtil.isNotBlank(saved.getTencentSecretKey())) {
            return saved.getTencentSecretKey();
        }
        return submitted;
    }

    /**
     * 将审查结果转换为连通性测试结果（回显 suggestion/label/score/raw）。
     *
     * @param result 审查结果
     * @return 测试结果
     */
    private ConfigTestResult buildResult(ModerationResult result) {
        if (result == null || result.isError()) {
            ConfigTestResult fail = ConfigTestResult.fail("图片审查调用失败");
            fail.setDetails(result == null ? null : result.getErrorMessage());
            return fail;
        }
        // 连通成功：回显建议/标签/分数
        String summary = StrUtil.format("suggestion={}, label={}, score={}",
                StrUtil.trimToEmpty(result.getSuggestion()),
                StrUtil.trimToEmpty(result.getLabel()),
                result.getScore());
        ConfigTestResult ok = ConfigTestResult.ok("调用成功:" + StrUtil.trimToEmpty(result.getSuggestion()), "tencent");
        // 原始 JSON 放入明细，仅超管可见
        ok.setDetails(summary + "; raw=" + StrUtil.trimToEmpty(result.getRawJson()));
        return ok;
    }
}
