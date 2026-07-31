package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidRolePropSceneForm;

/**
 * 角色道具场景形态(从)Service接口
 *
 * @author 视觉AID
 */
public interface IAidRolePropSceneFormService extends IService<AidRolePropSceneForm>
{
    /**
     * 查询角色道具场景形态(从)
     *
     * @param id 角色道具场景形态(从)主键
     * @return 角色道具场景形态(从)
     */
    public AidRolePropSceneForm selectAidRolePropSceneFormById(Long id);

    /**
     * 查询角色道具场景形态(从)列表
     *
     * @param aidRolePropSceneForm 角色道具场景形态(从)
     * @return 角色道具场景形态(从)集合
     */
    public List<AidRolePropSceneForm> selectAidRolePropSceneFormList(AidRolePropSceneForm aidRolePropSceneForm);

    /**
     * 新增角色道具场景形态(从)
     *
     * @param aidRolePropSceneForm 角色道具场景形态(从)
     * @return 结果
     */
    public int insertAidRolePropSceneForm(AidRolePropSceneForm aidRolePropSceneForm);

    /**
     * 修改角色道具场景形态(从)
     *
     * @param aidRolePropSceneForm 角色道具场景形态(从)
     * @return 结果
     */
    public int updateAidRolePropSceneForm(AidRolePropSceneForm aidRolePropSceneForm);

    /**
     * 批量删除角色道具场景形态(从)
     *
     * @param ids 需要删除的角色道具场景形态(从)主键集合
     * @return 结果
     */
    public int deleteAidRolePropSceneFormByIds(Long[] ids);

    /**
     * 删除角色道具场景形态(从)信息
     *
     * @param id 角色道具场景形态(从)主键
     * @return 结果
     */
    public int deleteAidRolePropSceneFormById(Long id);
}
