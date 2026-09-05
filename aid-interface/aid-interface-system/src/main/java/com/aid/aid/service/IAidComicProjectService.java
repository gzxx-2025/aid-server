package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidComicProject;

/**
 * 漫剧项目主Service接口
 *
 * @author 视觉AID
 */
public interface IAidComicProjectService extends IService<AidComicProject>
{
    /**
     * 查询漫剧项目主
     *
     * @param id 漫剧项目主主键
     * @return 漫剧项目主
     */
    public AidComicProject selectAidComicProjectById(Long id);

    /**
     * 查询漫剧项目主列表
     *
     * @param aidComicProject 漫剧项目主
     * @return 漫剧项目主集合
     */
    public List<AidComicProject> selectAidComicProjectList(AidComicProject aidComicProject);

    /**
     * 新增漫剧项目主
     *
     * @param aidComicProject 漫剧项目主
     * @return 结果
     */
    public int insertAidComicProject(AidComicProject aidComicProject);

    /**
     * 修改漫剧项目主
     *
     * @param aidComicProject 漫剧项目主
     * @return 结果
     */
    public int updateAidComicProject(AidComicProject aidComicProject);

    /**
     * 批量删除漫剧项目主
     *
     * @param ids 需要删除的漫剧项目主主键集合
     * @return 结果
     */
    public int deleteAidComicProjectByIds(Long[] ids);

    /**
     * 删除漫剧项目主信息
     *
     * @param id 漫剧项目主主键
     * @return 结果
     */
    public int deleteAidComicProjectById(Long id);

}
