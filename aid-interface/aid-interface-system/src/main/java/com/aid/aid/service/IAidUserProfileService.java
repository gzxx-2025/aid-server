package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidUserProfile;
import com.aid.aid.domain.vo.AidUserProfileVo;

/**
 * 用户扩展信息Service接口
 *
 * @author 视觉AID
 */
public interface IAidUserProfileService extends IService<AidUserProfile>
{
    /**
     * 查询用户扩展信息
     *
     * @param id 用户扩展信息主键
     * @return 用户扩展信息
     */
    public AidUserProfile selectAidUserProfileById(Long id);

    /**
     * 查询用户扩展信息列表
     *
     * @param aidUserProfile 用户扩展信息
     * @return 用户扩展信息集合
     */
    public List<AidUserProfile> selectAidUserProfileList(AidUserProfile aidUserProfile);

    /**
     * 新增用户扩展信息
     *
     * @param aidUserProfile 用户扩展信息
     * @return 结果
     */
    public int insertAidUserProfile(AidUserProfile aidUserProfile);

    /**
     * 修改用户扩展信息
     *
     * @param aidUserProfile 用户扩展信息
     * @return 结果
     */
    public int updateAidUserProfile(AidUserProfile aidUserProfile);

    /**
     * 批量删除用户扩展信息
     *
     * @param ids 需要删除的用户扩展信息主键集合
     * @return 结果
     */
    public int deleteAidUserProfileByIds(Long[] ids);

    /**
     * 删除用户扩展信息信息
     *
     * @param id 用户扩展信息主键
     * @return 结果
     */
    public int deleteAidUserProfileById(Long id);

    /**
     * 按 userId 查询用户扩展信息。
     *
     * @param userId 用户 ID
     * @return 命中返回实体；否则返回 null
     */
    AidUserProfile getByUserId(Long userId);

    /**
     * 关联 sys_user 查询用户聚合信息列表（后台用户管理）。
     *
     * @param query 查询条件（userId/会员等级/实名/昵称/手机号/账号/状态）
     * @return 聚合用户信息列表
     */
    List<AidUserProfileVo> selectUserProfileVoList(AidUserProfileVo query);
}
