package com.aid.aid.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidStoryboardShotGroupPlan;
import com.aid.aid.mapper.AidStoryboardShotGroupPlanMapper;
import com.aid.aid.service.IAidStoryboardShotGroupPlanService;

/**
 * 分镜镜头组拆分计划 Service 实现
 *
 * @author 视觉AID
 */
@Service
public class AidStoryboardShotGroupPlanServiceImpl
        extends ServiceImpl<AidStoryboardShotGroupPlanMapper, AidStoryboardShotGroupPlan>
        implements IAidStoryboardShotGroupPlanService
{
}
