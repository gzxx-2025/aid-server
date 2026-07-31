package com.aid.aid.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidWechatNotifyLog;
import com.aid.aid.mapper.AidWechatNotifyLogMapper;
import com.aid.aid.service.IAidWechatNotifyLogService;

/**
 * 微信模板消息推送日志业务实现
 */
@Service
public class AidWechatNotifyLogServiceImpl
        extends ServiceImpl<AidWechatNotifyLogMapper, AidWechatNotifyLog>
        implements IAidWechatNotifyLogService
{
}
