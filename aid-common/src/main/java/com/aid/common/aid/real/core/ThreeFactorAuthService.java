package com.aid.common.aid.real.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.common.aid.real.entity.RealAuthResult;
import com.aid.common.aid.real.properties.RealAuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

/**
 * 手机号三要素认证服务（阿里云市场-万维易源）。
 *
 * @author 视觉AID
 */
@Slf4j
public class ThreeFactorAuthService implements RealAuthService {

    private static final String API_URL = "https://auditphone.showapi.com/phoneAudit";

    private final RealAuthProperties properties;
    private final RestTemplate restTemplate;

    public ThreeFactorAuthService(RealAuthProperties properties) {
        this.properties = properties;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public RealAuthResult verify(String realName, String idCard, String phone) {
        log.info("三要素实名认证开始: name={}, idCard={}, phone={}",
                maskName(realName), maskIdCard(idCard), maskPhone(phone));

        if (phone == null || phone.isEmpty()) {
            return RealAuthResult.fail("三要素认证需要提供手机号");
        }

        try {
            // 构建请求URL
            String url = API_URL +
                    "?name=" + realName +
                    "&idCard=" + idCard +
                    "&phone=" + phone;

            // 构建请求头
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "APPCODE " + properties.getAppCode());

            // 发送GET请求
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            log.debug("三要素实名认证响应: {}", response.getBody());

            // 解析结果
            return parseResponse(response.getBody());

        }  catch (Exception e) {
            log.error("三要素实名认证异常: {}", e.getMessage(), e);
            return RealAuthResult.fail("认证失败");
        }
    }

    @Override
    public String getAuthType() {
        return "threeFactor";
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
                log.error("三要素认证API调用失败: showapi_res_code={}, error={}", showapiResCode, errorMsg);
                return RealAuthResult.fail("认证服务异常，请稍后重试");
            }

            // 解析 showapi_res_body
            JSONObject body = json.getJSONObject("showapi_res_body");
            if (body == null) {
                log.error("三要素认证响应格式错误: body为空");
                return RealAuthResult.fail("认证服务异常，请稍后重试");
            }

            // 检查 ret_code，-1 表示业务错误
//            Integer retCode = body.getInteger("ret_code");
//            if (retCode != null && retCode == -1) {
//                Integer code = body.getInteger("code");
//                log.error("三要素认证业务失败: code={}, body={}", code, body.toJSONString());
//                return RealAuthResult.fail(getErrorMsg(code));
//            }

            // 检查 code，0 表示认证成功
            Integer code = body.getInteger("code");
            if (code != null && code == 0) {
                return RealAuthResult.success();
            }

            // 其他错误码
            log.error("三要素认证失败: code={}, body={}", code, body.toJSONString());
            return RealAuthResult.fail(getErrorMsg(code));

        } catch (Exception e) {
            log.error("解析三要素实名认证响应失败: {}", e.getMessage());
            return RealAuthResult.fail("认证服务异常，请稍后重试");
        }
    }

    /**
     * 根据错误码获取用户友好的错误信息
     */
    private String getErrorMsg(Integer code) {
        if (code == null) {
            return "请检查信息是否正确";
        }
        switch (code) {
            case 1:
                return "请检查信息是否正确";
            case 2:
                return "无该手机号记录";
            case 11:
                return "请填写完整的认证信息";
            case 12:
                return "身份证号码不合法";
            case 13:
                return "手机号码不合法";
            case 21:
                return "渠道升级暂停服务";
            case 22:
                return "渠道维护暂停服务";
            default:
                return "认证失败";
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

    /**
     * 手机号脱敏
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() <= 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
