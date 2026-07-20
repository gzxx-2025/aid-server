package com.aid.auth.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.aid.auth.service.WeChatLoginService;
import com.aid.common.aid.wxlogin.core.WxLoginTemplateFactory;
import com.aid.common.annotation.Anonymous;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.result.WxMpQrCodeTicket;
import me.chanjar.weixin.mp.bean.result.WxMpUser;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 微信扫码登录/绑定控制器
 *
 * @author 视觉AID
 */
@Slf4j
@Tag(name = "微信扫码登录/绑定", description = "微信扫码登录和绑定相关接口")
@RestController
@RequestMapping("/auth/wechat")
public class WeChatLoginController {

    @Resource
    private WxLoginTemplateFactory wxLoginTemplateFactory;

    @Resource
    private WeChatLoginService weChatLoginService;

    /**
     * 手动验证微信签名
     *
     * @param signature 签名
     * @param timestamp 时间戳
     * @param nonce     随机数
     * @return 是否验证通过
     */
    private boolean checkSignature(String signature, String timestamp, String nonce, String token) {
        if (StrUtil.hasBlank(signature, timestamp, nonce)) {
            return false;
        }
        String[] arr = new String[]{token, timestamp, nonce};
        Arrays.sort(arr);

        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            sb.append(s);
        }

        String computedSignature = DigestUtil.sha1Hex(sb.toString());

        return computedSignature.equals(signature);
    }

    /**
     * 获取登录二维码
     *
     * @param inviteCode 邀请码（可选；新用户扫码注册时绑定邀请关系，老用户登录时忽略）
     * @return 二维码信息
     */
    @Operation(summary = "获取登录二维码", description = "生成微信扫码登录二维码，可携带邀请码（仅新用户注册时生效）")
    @Anonymous
    @RequestMapping(value = "/qrcode", method = {RequestMethod.GET, RequestMethod.POST})
    public AjaxResult getQRCode(@RequestParam(required = false) String inviteCode) {
        if (!wxLoginTemplateFactory.isEnabled()) {
            return AjaxResult.error("微信公众号登录未启用");
        }

        try {
            WxMpService wxMpService = wxLoginTemplateFactory.getWxMpService();

            String sceneStr = UUID.randomUUID().toString().replace("-", "");

            WxMpQrCodeTicket ticket = wxMpService.getQrcodeService().qrCodeCreateTmpTicket(sceneStr, 300);
            String qrCodeUrl = wxMpService.getQrcodeService().qrCodePictureUrl(ticket.getTicket());

            // 邀请码随登录会话暂存 Redis，扫码注册时读取绑定邀请关系
            weChatLoginService.initLoginStatus(sceneStr, inviteCode);

            Map<String, Object> data = new HashMap<>();
            data.put("sceneStr", sceneStr);
            data.put("qrCodeUrl", qrCodeUrl);
            data.put("expireSeconds", 300);

            return AjaxResult.success(data);
        } catch (Exception e) {
            log.error("获取微信登录二维码失败", e);
            return AjaxResult.error("获取登录二维码失败");
        }
    }

    /**
     * 微信回调接口
     * 注意：微信回调是 POST 请求，数据格式为 XML
     *
     * @param requestBody XML请求体
     * @return 响应给微信的结果
     */
    @Operation(summary = "微信回调", description = "接收微信扫码事件回调")
    @Anonymous
    @PostMapping(value = "/callback", produces = "application/xml; charset=UTF-8")
    public String handleCallback(@RequestBody String requestBody,
                                 @RequestParam(required = false) String signature,
                                 @RequestParam(required = false) String timestamp,
                                 @RequestParam(required = false) String nonce,
                                 @RequestParam(value = "encrypt_type", required = false) String encryptType,
                                 @RequestParam(value = "msg_signature", required = false) String msgSignature) {
        // 请求 body 含 openid/昵称等 PII，仅打印长度便于排查
        log.info("收到微信回调: signature={}, timestamp={}, nonce={}, encryptType={}, bodyLen={}",
                signature, timestamp, nonce, encryptType, requestBody == null ? 0 : requestBody.length());

        try {
            if (!wxLoginTemplateFactory.isEnabled()) {
                log.warn("微信公众号登录未启用");
                return "fail";
            }

            // token 未配置时直接拒绝回调
            String token = wxLoginTemplateFactory.getCurrentConfig().get("wxLoginToken");
            if (token == null || token.isEmpty()) {
                log.error("微信回调 token 未配置，拒绝处理回调");
                return "fail";
            }

            if (!checkSignature(signature, timestamp, nonce, token)) {
                log.warn("微信回调签名验证失败");
                return "fail";
            }

            WxMpService wxMpService = wxLoginTemplateFactory.getWxMpService();

            WxMpXmlMessage message = parseCallbackMessage(requestBody, timestamp, nonce, encryptType, msgSignature, wxMpService);
            String event = message.getEvent();
            String openId = message.getFromUser();
            String eventKey = message.getEventKey(); // 扫码时的场景值

            // openId 属于用户 PII，仅打印哈希前 8 位用于追踪
            log.info("微信回调解析: event={}, openIdHash={}, eventKey={}",
                    event, hashOpenId(openId), eventKey);

            String sceneStr = eventKey;
            if (eventKey != null && eventKey.startsWith("qrscene_")) {
                sceneStr = eventKey.replace("qrscene_", "");
            }

            if ("SCAN".equals(event) || "subscribe".equals(event)) {
                WxMpUser wxMpUser = wxMpService.getUserService().userInfo(openId);

                // 根据场景值前缀区分登录和绑定
                if (sceneStr != null && sceneStr.startsWith("bind_")) {
                    // 绑定场景
                    try {
                        weChatLoginService.handleBindScan(sceneStr, openId, wxMpUser);
                    } catch (Exception e) {
                        log.error("处理微信绑定扫码失败: sceneStr={}", sceneStr, e);
                        weChatLoginService.markBindFailed(sceneStr, "绑定失败");
                    }
                } else {
                    // 登录场景
                    try {
                        weChatLoginService.handleScan(sceneStr, openId, wxMpUser);
                    } catch (Exception e) {
                        log.error("处理微信登录扫码失败: sceneStr={}", sceneStr, e);
                        weChatLoginService.markLoginFailed(sceneStr, "登录失败");
                    }
                }
            }

            return "success"; // 必须返回 success 给微信

        } catch (Exception e) {
            log.error("处理微信回调异常", e);
            return "fail";
        }
    }

    /**
     * 解析微信回调 XML（支持明文与 AES 加密两种模式）。
     */
    private WxMpXmlMessage parseCallbackMessage(String requestBody, String timestamp, String nonce,
                                                String encryptType, String msgSignature, WxMpService wxMpService) {
        if (!"aes".equalsIgnoreCase(encryptType)) {
            return WxMpXmlMessage.fromXml(requestBody);
        }
        if (StrUtil.hasBlank(msgSignature, wxMpService.getWxMpConfigStorage().getAesKey())) {
            log.error("微信加密回调配置缺失: msgSignatureBlank={}, aesKeyBlank={}",
                    StrUtil.isBlank(msgSignature), StrUtil.isBlank(wxMpService.getWxMpConfigStorage().getAesKey()));
            throw new IllegalStateException("微信加密未配置");
        }
        return WxMpXmlMessage.fromEncryptedXml(
                requestBody,
                wxMpService.getWxMpConfigStorage(),
                timestamp,
                nonce,
                msgSignature);
    }

    /**
     * 微信服务器验证接口（GET请求）。
     *
     * @param signature 签名
     * @param timestamp 时间戳
     * @param nonce     随机数
     * @param echostr   随机字符串
     * @return echostr
     */
    @Operation(summary = "微信服务器验证", description = "微信服务器配置验证接口")
    @Anonymous
    @GetMapping("/callback")
    public String verify(@RequestParam(required = false) String signature,
                         @RequestParam(required = false) String timestamp,
                         @RequestParam(required = false) String nonce,
                         @RequestParam(required = false) String echostr) {
        // token 未配置时直接拒绝验证
        String token = wxLoginTemplateFactory.getCurrentConfig().get("wxLoginToken");
        if (token == null || token.isEmpty()) {
            log.error("微信服务器验证: token 未配置，拒绝");
            return "fail";
        }

        log.info("微信服务器验证: signature={}, timestamp={}, nonce={}, echostr={}",
                signature, timestamp, nonce, echostr);

        try {
            if (checkSignature(signature, timestamp, nonce, token)) {
                log.info("微信服务器验证成功");
                return echostr;
            } else {
                log.warn("微信服务器验证失败: 签名不匹配");
            }
        } catch (Exception e) {
            log.error("微信服务器验证失败", e);
        }
        return "fail";
    }

    /**
     * 轮询检查登录状态
     *
     * @param sceneStr 场景值
     * @return 登录状态
     */
    @Operation(summary = "检查登录状态", description = "前端轮询检查微信扫码登录状态")
    @Anonymous
    @RequestMapping(value = "/check", method = {RequestMethod.GET, RequestMethod.POST})
    public AjaxResult checkLogin(@RequestParam String sceneStr) {
        return weChatLoginService.checkLoginStatus(sceneStr);
    }
    /**
     * 获取绑定二维码
     * 需要用户已登录
     *
     * @return 二维码信息
     */
    @Operation(summary = "获取绑定二维码", description = "生成微信扫码绑定二维码（需要登录）")
    @RequestMapping(value = "/bind/qrcode", method = {RequestMethod.GET, RequestMethod.POST})
    public AjaxResult getBindQRCode() {
        if (!wxLoginTemplateFactory.isEnabled()) {
            return AjaxResult.error("微信公众号登录未启用");
        }

        try {
            Long userId = SecurityUtils.getUserId();

            WxMpService wxMpService = wxLoginTemplateFactory.getWxMpService();

            String sceneStr = "bind_" + UUID.randomUUID().toString().replace("-", "");

            WxMpQrCodeTicket ticket = wxMpService.getQrcodeService().qrCodeCreateTmpTicket(sceneStr, 300);
            String qrCodeUrl = wxMpService.getQrcodeService().qrCodePictureUrl(ticket.getTicket());

            weChatLoginService.initBindStatus(sceneStr, userId);

            Map<String, Object> data = new HashMap<>();
            data.put("sceneStr", sceneStr);
            data.put("qrCodeUrl", qrCodeUrl);
            data.put("expireSeconds", 300);

            return AjaxResult.success(data);
        } catch (Exception e) {
            log.error("获取微信绑定二维码失败", e);
            return AjaxResult.error("获取绑定二维码失败");
        }
    }

    /**
     * 轮询检查绑定状态
     *
     * @param sceneStr 场景值
     * @return 绑定状态
     */
    @Operation(summary = "检查绑定状态", description = "前端轮询检查微信扫码绑定状态")
    @RequestMapping(value = "/bind/check", method = {RequestMethod.GET, RequestMethod.POST})
    public AjaxResult checkBind(@RequestParam String sceneStr) {
        return weChatLoginService.checkBindStatus(sceneStr);
    }

    /**
     * openId 哈希（仅用于日志追踪），SHA-256 后取前 8 位 hex。
     * 只记录稳定哈希，便于运维追踪同一用户但不暴露 PII 原值。
     */
    private String hashOpenId(String openId) {
        if (openId == null || openId.isEmpty()) {
            return "";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(openId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4 && i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "***";
        }
    }
}
