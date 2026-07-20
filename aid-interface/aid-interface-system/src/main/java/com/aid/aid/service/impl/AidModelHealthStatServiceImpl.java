package com.aid.aid.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidModelHealthStat;
import com.aid.aid.mapper.AidModelHealthStatMapper;
import com.aid.aid.service.IAidModelHealthStatService;

/**
 * AI模型健康统计Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidModelHealthStatServiceImpl extends ServiceImpl<AidModelHealthStatMapper, AidModelHealthStat>
        implements IAidModelHealthStatService
{
}
