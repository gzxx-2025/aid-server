package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidComicEpisode;

/**
 * 剧集信息Service接口
 *
 * @author 视觉AID
 */
public interface IAidComicEpisodeService extends IService<AidComicEpisode>
{
    /**
     * 查询剧集信息
     *
     * @param id 剧集信息主键
     * @return 剧集信息
     */
    public AidComicEpisode selectAidComicEpisodeById(Long id);

    /**
     * 查询剧集信息列表
     *
     * @param aidComicEpisode 剧集信息
     * @return 剧集信息集合
     */
    public List<AidComicEpisode> selectAidComicEpisodeList(AidComicEpisode aidComicEpisode);

    /**
     * 新增剧集信息
     *
     * @param aidComicEpisode 剧集信息
     * @return 结果
     */
    public int insertAidComicEpisode(AidComicEpisode aidComicEpisode);

    /**
     * 修改剧集信息
     *
     * @param aidComicEpisode 剧集信息
     * @return 结果
     */
    public int updateAidComicEpisode(AidComicEpisode aidComicEpisode);

    /**
     * 批量删除剧集信息
     *
     * @param ids 需要删除的剧集信息主键集合
     * @return 结果
     */
    public int deleteAidComicEpisodeByIds(Long[] ids);

    /**
     * 删除剧集信息信息
     *
     * @param id 剧集信息主键
     * @return 结果
     */
    public int deleteAidComicEpisodeById(Long id);
}
