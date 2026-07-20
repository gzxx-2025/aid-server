package com.aid.aid.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidOnboardingTourConfig;
import com.aid.aid.mapper.OnboardingTourConfigMapper;
import com.aid.aid.service.IOnboardingTourConfigService;
import org.springframework.stereotype.Service;

/**
 * 用户引导 Tour 配置 Service 实现
 *
 * @author 视觉AID
 */
@Service
public class OnboardingTourConfigServiceImpl extends ServiceImpl<OnboardingTourConfigMapper, AidOnboardingTourConfig>
        implements IOnboardingTourConfigService
{
}
