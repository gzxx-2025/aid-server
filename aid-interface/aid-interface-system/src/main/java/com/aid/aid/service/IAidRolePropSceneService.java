package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidRolePropScene;

/**
 * 角色道具场景Service接口
 *
 * @author 视觉AID
 */
public interface IAidRolePropSceneService extends IService<AidRolePropScene>
{
    /**
     * 查询角色道具场景
     *
     * @param id 角色道具场景主键
     * @return 角色道具场景
     */
    public AidRolePropScene selectAidRolePropSceneById(Long id);

    /**
     * 查询角色道具场景列表
     *
     * @param aidRolePropScene 角色道具场景
     * @return 角色道具场景集合
     */
    public List<AidRolePropScene> selectAidRolePropSceneList(AidRolePropScene aidRolePropScene);

    /**
     * 新增角色道具场景
     *
     * @param aidRolePropScene 角色道具场景
     * @return 结果
     */
    public int insertAidRolePropScene(AidRolePropScene aidRolePropScene);

    /**
     * 修改角色道具场景
     *
     * @param aidRolePropScene 角色道具场景
     * @return 结果
     */
    public int updateAidRolePropScene(AidRolePropScene aidRolePropScene);

    /**
     * 批量删除角色道具场景
     *
     * @param ids 需要删除的角色道具场景主键集合
     * @return 结果
     */
    public int deleteAidRolePropSceneByIds(Long[] ids);

    /**
     * 删除角色道具场景信息
     *
     * @param id 角色道具场景主键
     * @return 结果
     */
    public int deleteAidRolePropSceneById(Long id);
}
