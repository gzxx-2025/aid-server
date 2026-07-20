package com.aid.notify.wechat.service;

import java.util.List;

import com.aid.notify.wechat.config.WechatNotifyConfig;
import com.aid.notify.wechat.vo.WechatNotifyPublicVO;
import com.aid.notify.wechat.vo.WechatNotifyStatusVO;

/**
 * 微信模板消息配置服务。
 */
public interface IWechatNotifyConfigService
{
    String CATEGORY = "wx_notify";

    String CONFIG_NAME = "templateConfig";

    WechatNotifyConfig getConfig();

    WechatNotifyConfig saveConfig(WechatNotifyConfig config);

    WechatNotifyStatusVO getStatus();

    WechatNotifyPublicVO getPublicStatus();

    List<String> getRules();
}
