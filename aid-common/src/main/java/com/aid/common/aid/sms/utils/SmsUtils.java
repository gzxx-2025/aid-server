package com.aid.common.aid.sms.utils;

import com.aid.common.aid.sms.core.SmsTemplateFactory;
import com.aid.common.aid.sms.entity.SmsResult;
import com.aid.common.aid.sms.exception.SmsException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 短信工具类
 *
 * @author 视觉AID
 */
@Component
public class SmsUtils {

    private static SmsTemplateFactory smsTemplateFactory;

    public SmsUtils(SmsTemplateFactory factory) {
        SmsUtils.smsTemplateFactory = factory;
    }

    /**
     * 发送短信
     *
     * @param phones     电话号(多个逗号分割)
     * @param templateId 模板id
     * @param param      模板参数
     * @return 发送结果
     */
    public static SmsResult send(String phones, String templateId, Map<String, String> param) {
        checkInit();
        return smsTemplateFactory.send(phones, templateId, param);
    }

    /**
     * 发送验证码（简化方法）
     *
     * @param phone 手机号
     * @param code  验证码
     * @return 发送结果
     */
    public static SmsResult sendCode(String phone, String code) {
        checkInit();
        return smsTemplateFactory.sendCode(phone, code);
    }

    /**
     * 刷新配置（配置页面点击"刷新配置"时调用）
     */
    public static void refresh() {
        if (smsTemplateFactory != null) {
            smsTemplateFactory.refresh();
        }
    }

    /**
     * 获取当前配置信息（供前端展示）
     */
    public static Map<String, String> getCurrentConfig() {
        checkInit();
        return smsTemplateFactory.getCurrentConfig();
    }

    private static void checkInit() {
        if (smsTemplateFactory == null) {
            throw new SmsException("短信服务未初始化");
        }
    }
}
