package com.aid.aid.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidScenePlot;
import com.aid.aid.mapper.AidScenePlotMapper;
import com.aid.aid.service.IAidScenePlotService;

/**
 * 剧情节拍 Service 实现。
 *
 * @author 视觉AID
 */
@Service
public class AidScenePlotServiceImpl extends ServiceImpl<AidScenePlotMapper, AidScenePlot>
        implements IAidScenePlotService
{
}
