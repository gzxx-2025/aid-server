package com.aid.aid.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidFaq;

/**
 * 常见问题（FAQ）Service 接口
 *
 * @author 视觉AID
 */
public interface IAidFaqService extends IService<AidFaq>
{
    /**
     * 查询常见问题
     *
     * @param id 常见问题主键
     * @return 常见问题
     */
    AidFaq selectAidFaqById(Long id);

    /**
     * 查询常见问题列表
     *
     * @param aidFaq 常见问题
     * @return 常见问题集合
     */
    List<AidFaq> selectAidFaqList(AidFaq aidFaq);

    /**
     * 新增常见问题
     *
     * @param aidFaq 常见问题
     * @return 结果
     */
    int insertAidFaq(AidFaq aidFaq);

    /**
     * 修改常见问题
     *
     * @param aidFaq 常见问题
     * @return 结果
     */
    int updateAidFaq(AidFaq aidFaq);

    /**
     * 批量删除常见问题
     *
     * @param ids 需要删除的常见问题主键集合
     * @return 结果
     */
    int deleteAidFaqByIds(Long[] ids);

    /**
     * 删除常见问题信息
     *
     * @param id 常见问题主键
     * @return 结果
     */
    int deleteAidFaqById(Long id);
}
