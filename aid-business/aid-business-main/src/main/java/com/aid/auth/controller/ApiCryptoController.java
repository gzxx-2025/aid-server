package com.aid.auth.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.auth.service.ApiCryptoService;
import com.aid.common.aid.crypto.annotation.CryptoIgnore;
import com.aid.common.annotation.Anonymous;
import com.aid.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 接口加密引导控制器（C 端）。
 *
 * @author 视觉AID
 */
@Slf4j
@Tag(name = "接口加密", description = "RSA 公钥下发")
@RestController
@RequestMapping("/auth/crypto")
public class ApiCryptoController {

    @Resource
    private ApiCryptoService apiCryptoService;

    /**
     * 获取接口加密公钥。入参无；
     * 出参 data 含 publicKey（RSA 公钥，Base64）、algorithm（RSA-OAEP-SHA256）、serverTime（服务端时间戳）。
     *
     * @return 公钥信息
     */
    @Operation(summary = "获取加密公钥", description = "下发 RSA 公钥，前端用于加密一次性 AES 密钥")
    @Anonymous
    @CryptoIgnore
    @PostMapping("/public-key")
    public AjaxResult publicKey() {
        return AjaxResult.success(apiCryptoService.getPublicKey());
    }
}
