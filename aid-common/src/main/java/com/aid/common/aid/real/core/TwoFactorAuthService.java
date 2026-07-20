package com.aid.common.aid.real.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.common.aid.real.entity.RealAuthResult;
import com.aid.common.aid.real.properties.RealAuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 身份证二要素认证服务（阿里云市场-万维易源）。
 *
 * @author 视觉AID
 */
@Slf4j
public class TwoFactorAuthService implements RealAuthService {

    private static final String API_URL = "https://idcard3.market.alicloudapi.com/idcardAudit";

    private final RealAuthProperties properties;
    private final RestTemplate restTemplate;

    public TwoFactorAuthService(RealAuthProperties properties) {
        this.properties = properties;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public RealAuthResult verify(String realName, String idCard, String phone) {
        log.info("二要素实名认证开始: name={}, idCard={}", maskName(realName), maskIdCard(idCard));

        // 检查 AppCode
        if (properties.getAppCode() == null || properties.getAppCode().trim().isEmpty()) {
            return RealAuthResult.fail("AppCode未配置，请在后台配置实名认证AppCode");
        }

        try {
            // 构建请求URL
            String url = API_URL + "?name=" + realName + "&idcard=" + idCard;

            // 构建请求头 - 注意 Authorization 格式
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // 阿里云市场要求的格式: "APPCODE xxx" (注意是 APPCODE 不是 APP)
            headers.set("Authorization", "APPCODE " + properties.getAppCode().trim());

            log.debug("二要素实名认证请求URL: {}", API_URL);
            log.debug("AppCode前4位: {}",
                properties.getAppCode().length() > 4 ? properties.getAppCode().substring(0, 4) + "***" : "***");

            // 发送GET请求
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            log.debug("二要素实名认证响应状态: {}", response.getStatusCode());
            log.debug("二要素实名认证响应: {}", response.getBody());

            // 解析结果
            return parseResponse(response.getBody());

        } catch (Exception e) {
            log.error("二要素实名认证异常: {}", e.getMessage(), e);
            return RealAuthResult.fail("认证失败");
        }
    }

    @Override
    public String getAuthType() {
        return "twoFactor";
    }

    /**
     * 解析API响应。
     */
    private RealAuthResult parseResponse(String responseBody) {
        try {
            JSONObject json = JSON.parseObject(responseBody);

            // 检查 showapi_res_code
            Integer showapiResCode = json.getInteger("showapi_res_code");
            if (showapiResCode == null || showapiResCode != 0) {
                String errorMsg = json.getString("showapi_res_error");
                log.error("二要素认证API调用失败: showapi_res_code={}, error={}", showapiResCode, errorMsg);
                return RealAuthResult.fail("认证服务异常，请稍后重试");
            }

            // 解析 showapi_res_body
            JSONObject body = json.getJSONObject("showapi_res_body");
            if (body == null) {
                log.error("二要素认证响应格式错误: body为空");
                return RealAuthResult.fail("认证服务异常，请稍后重试");
            }

            // 检查 ret_code，-1 表示业务错误
            Integer retCode = body.getInteger("ret_code");
            if (retCode != null && retCode == -1) {
                String code = body.getString("code");
                log.error("二要素认证业务失败: code={}, body={}", code, body.toJSONString());
                return RealAuthResult.fail(getErrorMsg(code));
            }

            // 检查 code，"0" 表示匹配成功
            String code = body.getString("code");
            if ("0".equals(code)) {
                return RealAuthResult.success();
            }

            // 其他错误码
            log.error("二要素认证失败: code={}, body={}", code, body.toJSONString());
            return RealAuthResult.fail(getErrorMsg(code));

        } catch (Exception e) {
            log.error("解析二要素实名认证响应失败: {}", e.getMessage());
            return RealAuthResult.fail("认证服务异常，请稍后重试");
        }
    }

    /**
     * 根据错误码获取用户友好的错误信息
     */
    private String getErrorMsg(String code) {
        if (code == null) {
            return "认证失败，请检查信息是否正确";
        }
        switch (code) {
            case "1":
                return "身份证与姓名不匹配";
            case "2":
                return "无此身份证号码";
            case "12":
                return "身份证号码不合法";
            case "101":
                return "操作过于频繁，请60秒后重试";
            case "103":
                return "核验次数超限，请明天再试";
            default:
                return "认证失败，请检查信息是否正确";
        }
    }

    /**
     * 姓名脱敏
     */
    private String maskName(String name) {
        if (name == null || name.length() <= 1) {
            return name;
        }
        return name.charAt(0) + "**";
    }

    /**
     * 身份证脱敏
     */
    private String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() <= 6) {
            return idCard;
        }
        return idCard.substring(0, 3) + "***********" + idCard.substring(idCard.length() - 4);
    }
}
