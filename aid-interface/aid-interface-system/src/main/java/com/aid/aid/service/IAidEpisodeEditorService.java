package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidEpisodeEditor;

/**
 * 剧集视频剪辑与成片最新状态Service接口
 *
 * @author 视觉AID
 */
public interface IAidEpisodeEditorService extends IService<AidEpisodeEditor>
{
    /**
     * 查询剧集视频剪辑与成片最新状态
     *
     * @param id 剧集视频剪辑与成片最新状态主键
     * @return 剧集视频剪辑与成片最新状态
     */
    public AidEpisodeEditor selectAidEpisodeEditorById(Long id);

    /**
     * 查询剧集视频剪辑与成片最新状态列表
     *
     * @param aidEpisodeEditor 剧集视频剪辑与成片最新状态
     * @return 剧集视频剪辑与成片最新状态集合
     */
    public List<AidEpisodeEditor> selectAidEpisodeEditorList(AidEpisodeEditor aidEpisodeEditor);

    /**
     * 新增剧集视频剪辑与成片最新状态
     *
     * @param aidEpisodeEditor 剧集视频剪辑与成片最新状态
     * @return 结果
     */
    public int insertAidEpisodeEditor(AidEpisodeEditor aidEpisodeEditor);

    /**
     * 修改剧集视频剪辑与成片最新状态
     *
     * @param aidEpisodeEditor 剧集视频剪辑与成片最新状态
     * @return 结果
     */
    public int updateAidEpisodeEditor(AidEpisodeEditor aidEpisodeEditor);

    /**
     * 批量删除剧集视频剪辑与成片最新状态
     *
     * @param ids 需要删除的剧集视频剪辑与成片最新状态主键集合
     * @return 结果
     */
    public int deleteAidEpisodeEditorByIds(Long[] ids);

    /**
     * 删除剧集视频剪辑与成片最新状态信息
     *
     * @param id 剧集视频剪辑与成片最新状态主键
     * @return 结果
     */
    public int deleteAidEpisodeEditorById(Long id);
}
