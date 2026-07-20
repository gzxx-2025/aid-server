package com.aid.aid.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidUserOnboardingProfile;
import com.aid.aid.mapper.UserOnboardingProfileMapper;
import com.aid.aid.service.IUserOnboardingProfileService;
import org.springframework.stereotype.Service;

/**
 * 用户引导全局配置 Service 实现
 *
 * @author 视觉AID
 */
@Service
public class UserOnboardingProfileServiceImpl extends ServiceImpl<UserOnboardingProfileMapper, AidUserOnboardingProfile>
        implements IUserOnboardingProfileService
{
}
