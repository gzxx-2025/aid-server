package com.aid.common.aid.wxpay.controller;

import com.aid.common.aid.wxpay.core.WxpayTemplateFactory;
import com.aid.common.core.domain.AjaxResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * 微信支付配置控制器
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/wxpay/config")
@RequiredArgsConstructor
public class WxpayConfigController {

    private final WxpayTemplateFactory wxpayTemplateFactory;

    /**
     * 获取当前生效的微信支付配置（脱敏）
     * 在配置页面展示当前使用的参数
     */
    @PreAuthorize("@ss.hasPermi('wxpay:config:query')")
    @GetMapping("/current")
    public AjaxResult getCurrentConfig() {
        Map<String, String> config = wxpayTemplateFactory.getCurrentConfig();
        return AjaxResult.success(config);
    }

    /**
     * 刷新配置
     * 在配置页面修改后点击"同步配置"按钮调用
     */
    @PreAuthorize("@ss.hasPermi('wxpay:config:refresh')")
    @PostMapping("/refresh")
    public AjaxResult refresh() {
        wxpayTemplateFactory.refresh();
        return AjaxResult.success("配置刷新成功");
    }

    /**
     * 解析上传的微信支付 V3 证书文件，提取证书序列号（serialNo）。
     *
     * @param file 微信商户平台下载的 apiclient_cert.pem
     * @return serialNo（大写十六进制）+ 证书有效期
     */
    @PreAuthorize("@ss.hasPermi('wxpay:config:refresh')")
    @PostMapping("/parse-cert")
    public AjaxResult parseCert(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return AjaxResult.error("请上传证书文件 apiclient_cert.pem");
        }
        try (InputStream in = file.getInputStream()) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(in);
            // 微信证书序列号 = X.509 证书序列号的大写十六进制
            String serialNo = cert.getSerialNumber().toString(16).toUpperCase();

            Map<String, Object> data = new HashMap<>();
            data.put("serialNo", serialNo);
            data.put("notAfter", new SimpleDateFormat("yyyy-MM-dd").format(cert.getNotAfter()));
            return AjaxResult.success(data);
        } catch (Exception e) {
            return AjaxResult.error("证书解析失败，请确认上传的是 apiclient_cert.pem 证书文件：" + e.getMessage());
        }
    }
}
