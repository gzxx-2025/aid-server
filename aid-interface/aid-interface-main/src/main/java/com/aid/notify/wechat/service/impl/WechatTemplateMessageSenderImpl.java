package com.aid.notify.wechat.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.common.aid.wxlogin.core.WxLoginTemplateFactory;
import com.aid.common.exception.ServiceException;
import com.aid.notify.wechat.service.IWechatTemplateMessageSender;
import com.aid.notify.wechat.vo.WechatTemplatePayload;
import com.aid.notify.wechat.vo.WechatTemplateSendResult;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;

/**
 * 微信模板消息发送器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatTemplateMessageSenderImpl implements IWechatTemplateMessageSender
{
    private static final String SEND_URL =
            "https://api.weixin.qq.com/cgi-bin/message/template/send?access_token=";

    private final WxLoginTemplateFactory wxLoginTemplateFactory;

    @Override
    public WechatTemplateSendResult send(WechatTemplatePayload payload)
    {
        validatePayload(payload);
        try
        {
            WxMpService wxMpService = wxLoginTemplateFactory.getWxMpServiceWithoutCheck();
            String accessToken = wxMpService.getAccessToken();
            JSONObject request = buildRequest(payload);
            try (HttpResponse response = HttpRequest.post(SEND_URL + accessToken)
                    .charset(StandardCharsets.UTF_8)
                    .body(request.toJSONString())
                    .timeout(10000)
                    .execute())
            {
                return parseResult(response.body());
            }
        }
        catch (Exception e)
        {
            log.error("微信模板消息发送异常: openid={}, templateId={}",
                    maskOpenid(payload.getOpenid()), payload.getTemplateId(), e);
            WechatTemplateSendResult result = new WechatTemplateSendResult();
            result.setErrcode(-1);
            result.setErrmsg(StrUtil.blankToDefault(e.getMessage(), "send failed"));
            result.setRawResponse(result.getErrmsg());
            return result;
        }
    }

    public JSONObject buildRequest(WechatTemplatePayload payload)
    {
        JSONObject request = new JSONObject();
        request.put("touser", payload.getOpenid());
        request.put("template_id", payload.getTemplateId());
        if (StrUtil.isNotBlank(payload.getUrl()))
        {
            request.put("url", payload.getUrl());
        }
        if (StrUtil.isNotBlank(payload.getClientMsgId()))
        {
            request.put("client_msg_id", payload.getClientMsgId());
        }
        JSONObject data = new JSONObject();
        for (Map.Entry<String, String> entry : payload.getData().entrySet())
        {
            if (StrUtil.isBlank(entry.getKey()))
            {
                continue;
            }
            JSONObject value = new JSONObject();
            value.put("value", formatValue(entry.getKey(), entry.getValue()));
            data.put(entry.getKey(), value);
        }
        request.put("data", data);
        return request;
    }

    private WechatTemplateSendResult parseResult(String body)
    {
        WechatTemplateSendResult result = new WechatTemplateSendResult();
        result.setRawResponse(body);
        if (StrUtil.isBlank(body))
        {
            result.setErrcode(-1);
            result.setErrmsg("empty response");
            return result;
        }
        JSONObject json = JSON.parseObject(body);
        result.setErrcode(json.getInteger("errcode"));
        result.setErrmsg(json.getString("errmsg"));
        result.setMsgid(json.getLong("msgid"));
        return result;
    }

    private void validatePayload(WechatTemplatePayload payload)
    {
        if (Objects.isNull(payload) || StrUtil.isBlank(payload.getOpenid()))
        {
            throw new ServiceException("openid为空");
        }
        if (StrUtil.isBlank(payload.getTemplateId()))
        {
            throw new ServiceException("模板未配置");
        }
        if (payload.getData() == null || payload.getData().isEmpty())
        {
            throw new ServiceException("模板数据为空");
        }
    }

    private String formatValue(String keyword, String value)
    {
        String cleaned = StrUtil.blankToDefault(value, "-")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (StrUtil.isBlank(cleaned))
        {
            cleaned = "-";
        }
        String lower = keyword.toLowerCase();
        if (lower.startsWith("thing") || lower.startsWith("const"))
        {
            return limitByCodePoint(cleaned, 20);
        }
        if (lower.startsWith("amount") || lower.startsWith("time"))
        {
            return limitByCodePoint(cleaned, 20);
        }
        if (lower.startsWith("number") || lower.startsWith("character_string"))
        {
            return limitByCodePoint(cleaned, 32);
        }
        return limitByCodePoint(cleaned, 64);
    }

    private String limitByCodePoint(String value, int max)
    {
        if (value.codePointCount(0, value.length()) <= max)
        {
            return value;
        }
        int end = value.offsetByCodePoints(0, max);
        return value.substring(0, end);
    }

    private String maskOpenid(String openid)
    {
        if (StrUtil.isBlank(openid) || openid.length() <= 8)
        {
            return openid;
        }
        return openid.substring(0, 4) + "****" + openid.substring(openid.length() - 4);
    }
}
