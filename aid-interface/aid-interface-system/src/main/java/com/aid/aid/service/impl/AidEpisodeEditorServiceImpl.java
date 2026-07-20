package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import com.aid.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.mapper.AidEpisodeEditorMapper;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.service.IAidEpisodeEditorService;

/**
 * 剧集视频剪辑与成片最新状态Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidEpisodeEditorServiceImpl extends ServiceImpl<AidEpisodeEditorMapper, AidEpisodeEditor> implements IAidEpisodeEditorService
{
    @Autowired
    private AidEpisodeEditorMapper aidEpisodeEditorMapper;

    /**
     * 查询剧集视频剪辑与成片最新状态
     *
     * @param id 剧集视频剪辑与成片最新状态主键
     * @return 剧集视频剪辑与成片最新状态
     */
    @Override
    public AidEpisodeEditor selectAidEpisodeEditorById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询剧集视频剪辑与成片最新状态列表
     *
     * @param aidEpisodeEditor 剧集视频剪辑与成片最新状态
     * @return 剧集视频剪辑与成片最新状态
     */
    @Override
    public List<AidEpisodeEditor> selectAidEpisodeEditorList(AidEpisodeEditor aidEpisodeEditor)
    {
        LambdaQueryWrapper<AidEpisodeEditor> wrapper = Wrappers.lambdaQuery();
        if (aidEpisodeEditor != null)
        {
            if (aidEpisodeEditor.getProjectId() != null)
            {
                wrapper.eq(AidEpisodeEditor::getProjectId, aidEpisodeEditor.getProjectId());
            }
            if (aidEpisodeEditor.getEpisodeId() != null)
            {
                wrapper.eq(AidEpisodeEditor::getEpisodeId, aidEpisodeEditor.getEpisodeId());
            }
            if (aidEpisodeEditor.getUserId() != null)
            {
                wrapper.eq(AidEpisodeEditor::getUserId, aidEpisodeEditor.getUserId());
            }
            if (aidEpisodeEditor.getExportStatus() != null)
            {
                wrapper.eq(AidEpisodeEditor::getExportStatus, aidEpisodeEditor.getExportStatus());
            }
        }
        wrapper.orderByDesc(AidEpisodeEditor::getId);
        return this.list(wrapper);
    }

    /**
     * 新增剧集视频剪辑与成片最新状态
     *
     * @param aidEpisodeEditor 剧集视频剪辑与成片最新状态
     * @return 结果
     */
    @Override
    public int insertAidEpisodeEditor(AidEpisodeEditor aidEpisodeEditor)
    {
        aidEpisodeEditor.setCreateTime(DateUtils.getNowDate());
        return this.save(aidEpisodeEditor) ? 1 : 0;
    }

    /**
     * 修改剧集视频剪辑与成片最新状态
     *
     * @param aidEpisodeEditor 剧集视频剪辑与成片最新状态
     * @return 结果
     */
    @Override
    public int updateAidEpisodeEditor(AidEpisodeEditor aidEpisodeEditor)
    {
        aidEpisodeEditor.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidEpisodeEditor) ? 1 : 0;
    }

    /**
     * 批量删除剧集视频剪辑与成片最新状态
     *
     * @param ids 需要删除的剧集视频剪辑与成片最新状态主键
     * @return 结果
     */
    @Override
    public int deleteAidEpisodeEditorByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除剧集视频剪辑与成片最新状态信息
     *
     * @param id 剧集视频剪辑与成片最新状态主键
     * @return 结果
     */
    @Override
    public int deleteAidEpisodeEditorById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}
