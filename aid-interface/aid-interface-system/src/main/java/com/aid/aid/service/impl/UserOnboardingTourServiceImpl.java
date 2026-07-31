package com.aid.aid.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidUserOnboardingTour;
import com.aid.aid.mapper.UserOnboardingTourMapper;
import com.aid.aid.service.IUserOnboardingTourService;
import org.springframework.stereotype.Service;

/**
 * 用户引导 Tour 进度 Service 实现
 *
 * @author 视觉AID
 */
@Service
public class UserOnboardingTourServiceImpl extends ServiceImpl<UserOnboardingTourMapper, AidUserOnboardingTour>
        implements IUserOnboardingTourService
{
}
