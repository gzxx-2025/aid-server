package com.aid.aid.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.vo.AidPublicProjectVo;
import com.aid.aid.domain.vo.AidPublishItemVo;

/**
 * 漫剧项目主Mapper接口
 *
 * @author 视觉AID
 */
public interface AidComicProjectMapper extends BaseMapper<AidComicProject>
{
    /**
     * 后台发布管理列表（联表作者信息）
     *
     * @param publishState 发布状态筛选（approved=过审未发布 published=已发布，可空）
     * @param projectName  作品名称模糊搜索（可空）
     * @param projectType  作品类型筛选（可空）
     * @param keyword      作者关键字（昵称/邮箱/手机号，可空）
     * @return 发布管理列表
     */
    List<AidPublishItemVo> selectPublishItemVoList(@Param("publishState") String publishState,
                                                   @Param("projectName") String projectName,
                                                   @Param("projectType") String projectType,
                                                   @Param("keyword") String keyword);

    /**
     * C端公开广场列表（联表作者昵称，已公开且状态审核中/审核通过，按发布时间倒序）
     *
     * @param projectName 作品名称模糊搜索（可空）
     * @param projectType 作品类型筛选（movie/series，可空）
     * @return 公开广场项目列表
     */
    List<AidPublicProjectVo> selectPublicProjectVoList(@Param("projectName") String projectName,
                                                       @Param("projectType") String projectType);
}
