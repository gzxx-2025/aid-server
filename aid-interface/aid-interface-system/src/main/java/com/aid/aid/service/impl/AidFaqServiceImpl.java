package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.domain.AidFaq;
import com.aid.aid.mapper.AidFaqMapper;
import com.aid.aid.service.IAidFaqService;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.SecurityUtils;

import cn.hutool.core.util.StrUtil;

/**
 * 常见问题（FAQ）Service 业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidFaqServiceImpl extends ServiceImpl<AidFaqMapper, AidFaq> implements IAidFaqService
{
    /**
     * 查询常见问题
     *
     * @param id 常见问题主键
     * @return 常见问题
     */
    @Override
    public AidFaq selectAidFaqById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询常见问题列表
     *
     * @param aidFaq 常见问题
     * @return 常见问题
     */
    @Override
    public List<AidFaq> selectAidFaqList(AidFaq aidFaq)
    {
        LambdaQueryWrapper<AidFaq> wrapper = Wrappers.lambdaQuery();
        if (aidFaq != null)
        {
            // 标题模糊检索
            if (StrUtil.isNotBlank(aidFaq.getTitle()))
            {
                wrapper.like(AidFaq::getTitle, aidFaq.getTitle());
            }
            // 分类精确检索
            if (StrUtil.isNotBlank(aidFaq.getCategory()))
            {
                wrapper.eq(AidFaq::getCategory, aidFaq.getCategory());
            }
            // 状态精确检索
            if (StrUtil.isNotBlank(aidFaq.getStatus()))
            {
                wrapper.eq(AidFaq::getStatus, aidFaq.getStatus());
            }
        }
        // 排序：sort_order 升序，相同再按 id 倒序
        wrapper.orderByAsc(AidFaq::getSortOrder).orderByDesc(AidFaq::getId);
        return this.list(wrapper);
    }

    /**
     * 新增常见问题
     *
     * @param aidFaq 常见问题
     * @return 结果
     */
    @Override
    public int insertAidFaq(AidFaq aidFaq)
    {
        // 填充创建时间与创建者
        aidFaq.setCreateTime(DateUtils.getNowDate());
        aidFaq.setCreateBy(SecurityUtils.getUsername());
        return this.save(aidFaq) ? 1 : 0;
    }

    /**
     * 修改常见问题
     *
     * @param aidFaq 常见问题
     * @return 结果
     */
    @Override
    public int updateAidFaq(AidFaq aidFaq)
    {
        // 填充更新时间与更新者
        aidFaq.setUpdateTime(DateUtils.getNowDate());
        aidFaq.setUpdateBy(SecurityUtils.getUsername());
        return this.updateById(aidFaq) ? 1 : 0;
    }

    /**
     * 批量删除常见问题
     *
     * @param ids 需要删除的常见问题主键
     * @return 结果
     */
    @Override
    public int deleteAidFaqByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除常见问题信息
     *
     * @param id 常见问题主键
     * @return 结果
     */
    @Override
    public int deleteAidFaqById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
