package com.aid.config.test.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.moderation.ModerationResult;
import com.aid.config.test.tester.ImageModerationTester;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 图片内容安全审查测试入口（后台，multipart）。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aidconfig/imgmoderation")
@RequiredArgsConstructor
public class ImageModerationTestController extends BaseController {

    /**
     * 图片审查测试器（提供临时配置审查能力）。
     */
    private final ImageModerationTester imageModerationTester;

    /**
     * 图片审查测试：上传图 + 临时配置 → 回显完整原始审查结果。
     *
     * @param file       上传图片（可选，与 payloadJson.fileUrl 二选一）
     * @param payloadJson 临时配置 JSON 文本（含 tencentRegion/tencentSecretId/tencentSecretKey/bizType/fileUrl 等）
     * @return 完整原始审查结果，放在 data 字段返回
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @PostMapping("/test")
    public AjaxResult testImageModeration(@RequestPart(value = "file", required = false) MultipartFile file,
                                          @RequestPart("payloadJson") String payloadJson) {
        // 解析临时配置 JSON 文本为 Map（密钥仅内存流转，不落库）
        Map<String, Object> payload = parsePayload(payloadJson);
        String fileUrl = payload.get("fileUrl") == null ? null : String.valueOf(payload.get("fileUrl"));

        try {
            // 读取上传文件字节（可空）
            byte[] bytes = readBytes(file);
            String fileName = (file == null) ? null : file.getOriginalFilename();
            // 使用临时配置执行一次审查，返回厂商原始结果
            ModerationResult result = imageModerationTester.moderateForTest(payload, bytes, fileName, fileUrl);
            return AjaxResult.success(result);
        } catch (IllegalArgumentException e) {
            // 入参问题：返回友好提示
            log.error("图片审查测试入参异常, error={}", e.getMessage());
            return AjaxResult.error(e.getMessage());
        } catch (Exception e) {
            // 兜底：审查调用异常
            log.error("图片审查测试执行异常, error={}", e.getMessage(), e);
            return AjaxResult.error("图片审查测试失败");
        }
    }

    /**
     * 解析临时配置 JSON 文本为 Map。
     *
     * @param payloadJson JSON 文本
     * @return 配置 Map，解析失败返回空 Map
     */
    private Map<String, Object> parsePayload(String payloadJson) {
        if (StrUtil.isBlank(payloadJson)) {
            return new HashMap<>();
        }
        try {
            JSONObject json = JSON.parseObject(payloadJson);
            return json == null ? new HashMap<>() : json;
        } catch (Exception e) {
            // 解析失败按空配置处理，由测试器做凭证校验
            log.error("图片审查测试配置解析失败, error={}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 读取上传文件字节。
     *
     * @param file 上传文件，可空
     * @return 字节内容，无文件返回 null
     * @throws Exception 读取异常
     */
    private byte[] readBytes(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            return null;
        }
        return file.getBytes();
    }
}
