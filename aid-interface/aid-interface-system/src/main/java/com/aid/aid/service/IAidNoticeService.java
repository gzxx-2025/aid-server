package com.aid.aid.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidNotice;

/**
 * C 端公告 Service 接口（后台管理维护用）
 *
 * @author 视觉AID
 */
public interface IAidNoticeService extends IService<AidNotice>
{
    /**
     * 查询公告
     *
     * @param id 公告主键
     * @return 公告
     */
    AidNotice selectAidNoticeById(Long id);

    /**
     * 查询公告列表
     *
     * @param aidNotice 公告（标题/类型/状态作为检索条件）
     * @return 公告集合
     */
    List<AidNotice> selectAidNoticeList(AidNotice aidNotice);

    /**
     * 新增公告
     *
     * @param aidNotice 公告
     * @return 结果
     */
    int insertAidNotice(AidNotice aidNotice);

    /**
     * 修改公告
     *
     * @param aidNotice 公告
     * @return 结果
     */
    int updateAidNotice(AidNotice aidNotice);

    /**
     * 批量删除公告
     *
     * @param ids 需要删除的公告主键集合
     * @return 结果
     */
    int deleteAidNoticeByIds(Long[] ids);

    /**
     * 删除公告信息
     *
     * @param id 公告主键
     * @return 结果
     */
    int deleteAidNoticeById(Long id);
}
