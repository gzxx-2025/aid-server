package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import cn.hutool.core.util.StrUtil;
import com.aid.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.mapper.AidComicProjectMapper;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.vo.AidPublicProjectVo;
import com.aid.aid.domain.vo.AidPublishItemVo;
import com.aid.aid.service.IAidComicProjectService;

/**
 * 漫剧项目主Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidComicProjectServiceImpl extends ServiceImpl<AidComicProjectMapper, AidComicProject> implements IAidComicProjectService
{
    @Autowired
    private AidComicProjectMapper aidComicProjectMapper;

    /**
     * 查询漫剧项目主
     *
     * @param id 漫剧项目主主键
     * @return 漫剧项目主
     */
    @Override
    public AidComicProject selectAidComicProjectById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询漫剧项目主列表
     *
     * @param aidComicProject 漫剧项目主
     * @return 漫剧项目主
     */
    @Override
    public List<AidComicProject> selectAidComicProjectList(AidComicProject aidComicProject)
    {
        LambdaQueryWrapper<AidComicProject> wrapper = Wrappers.lambdaQuery();
        if (aidComicProject != null)
        {
            if (aidComicProject.getUserId() != null)
            {
                wrapper.eq(AidComicProject::getUserId, aidComicProject.getUserId());
            }
            if (StrUtil.isNotBlank(aidComicProject.getProjectName()))
            {
                wrapper.like(AidComicProject::getProjectName, aidComicProject.getProjectName());
            }
            if (StrUtil.isNotBlank(aidComicProject.getProjectType()))
            {
                wrapper.eq(AidComicProject::getProjectType, aidComicProject.getProjectType());
            }
            if (StrUtil.isNotBlank(aidComicProject.getAspectRatio()))
            {
                wrapper.eq(AidComicProject::getAspectRatio, aidComicProject.getAspectRatio());
            }
            if (StrUtil.isNotBlank(aidComicProject.getScriptType()))
            {
                wrapper.eq(AidComicProject::getScriptType, aidComicProject.getScriptType());
            }
            if (StrUtil.isNotBlank(aidComicProject.getVideoStyleType()))
            {
                wrapper.eq(AidComicProject::getVideoStyleType, aidComicProject.getVideoStyleType());
            }
            if (StrUtil.isNotBlank(aidComicProject.getDefaultGenMode()))
            {
                wrapper.eq(AidComicProject::getDefaultGenMode, aidComicProject.getDefaultGenMode());
            }
            if (StrUtil.isNotBlank(aidComicProject.getDefaultCreationMode()))
            {
                wrapper.eq(AidComicProject::getDefaultCreationMode, aidComicProject.getDefaultCreationMode());
            }
            if (aidComicProject.getCurrentStep() != null)
            {
                wrapper.eq(AidComicProject::getCurrentStep, aidComicProject.getCurrentStep());
            }
            if (aidComicProject.getStatus() != null)
            {
                wrapper.eq(AidComicProject::getStatus, aidComicProject.getStatus());
            }
            if (StrUtil.isNotBlank(aidComicProject.getIsPublic()))
            {
                wrapper.eq(AidComicProject::getIsPublic, aidComicProject.getIsPublic());
            }
        }
        wrapper.orderByDesc(AidComicProject::getId);
        return this.list(wrapper);
    }

    /**
     * 新增漫剧项目主
     *
     * @param aidComicProject 漫剧项目主
     * @return 结果
     */
    @Override
    public int insertAidComicProject(AidComicProject aidComicProject)
    {
        aidComicProject.setCreateTime(DateUtils.getNowDate());
        return this.save(aidComicProject) ? 1 : 0;
    }

    /**
     * 修改漫剧项目主
     *
     * @param aidComicProject 漫剧项目主
     * @return 结果
     */
    @Override
    public int updateAidComicProject(AidComicProject aidComicProject)
    {
        aidComicProject.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidComicProject) ? 1 : 0;
    }

    /**
     * 批量删除漫剧项目主
     *
     * @param ids 需要删除的漫剧项目主主键
     * @return 结果
     */
    @Override
    public int deleteAidComicProjectByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除漫剧项目主信息
     *
     * @param id 漫剧项目主主键
     * @return 结果
     */
    @Override
    public int deleteAidComicProjectById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }

    /**
     * 后台发布管理列表（联表作者信息，SQL 见 AidComicProjectMapper.xml）
     *
     * @param publishState 发布状态筛选（approved=过审未发布 published=已发布，可空）
     * @param projectName  作品名称模糊搜索（可空）
     * @param projectType  作品类型筛选（可空）
     * @param keyword      作者关键字（昵称/邮箱/手机号，可空）
     * @return 发布管理列表
     */
    @Override
    public List<AidPublishItemVo> selectPublishItemVoList(String publishState, String projectName,
                                                          String projectType, String keyword)
    {
        return aidComicProjectMapper.selectPublishItemVoList(publishState, projectName, projectType, keyword);
    }

    /**
     * C端公开广场列表（联表作者昵称，SQL 见 AidComicProjectMapper.xml）
     *
     * @param projectName 作品名称模糊搜索（可空）
     * @param projectType 作品类型筛选（movie/series，可空）
     * @return 公开广场项目列表
     */
    @Override
    public List<AidPublicProjectVo> selectPublicProjectVoList(String projectName, String projectType)
    {
        return aidComicProjectMapper.selectPublicProjectVoList(projectName, projectType);
    }
}
