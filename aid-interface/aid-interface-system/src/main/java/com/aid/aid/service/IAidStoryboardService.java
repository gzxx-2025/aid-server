package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidStoryboard;

/**
 * 分镜时间轴主Service接口
 *
 * @author 视觉AID
 */
public interface IAidStoryboardService extends IService<AidStoryboard>
{
    /**
     * 查询分镜时间轴主
     *
     * @param id 分镜时间轴主主键
     * @return 分镜时间轴主
     */
    public AidStoryboard selectAidStoryboardById(Long id);

    /**
     * 查询分镜时间轴主列表
     *
     * @param aidStoryboard 分镜时间轴主
     * @return 分镜时间轴主集合
     */
    public List<AidStoryboard> selectAidStoryboardList(AidStoryboard aidStoryboard);

    /**
     * 新增分镜时间轴主
     *
     * @param aidStoryboard 分镜时间轴主
     * @return 结果
     */
    public int insertAidStoryboard(AidStoryboard aidStoryboard);

    /**
     * 修改分镜时间轴主
     *
     * @param aidStoryboard 分镜时间轴主
     * @return 结果
     */
    public int updateAidStoryboard(AidStoryboard aidStoryboard);

    /**
     * 批量删除分镜时间轴主
     *
     * @param ids 需要删除的分镜时间轴主主键集合
     * @return 结果
     */
    public int deleteAidStoryboardByIds(Long[] ids);

    /**
     * 删除分镜时间轴主信息
     *
     * @param id 分镜时间轴主主键
     * @return 结果
     */
    public int deleteAidStoryboardById(Long id);
}
