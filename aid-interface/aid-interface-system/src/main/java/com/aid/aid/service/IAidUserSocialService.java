package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidUserSocial;

/**
 * 用户第三方登录授权Service接口
 *
 * @author 视觉AID
 */
public interface IAidUserSocialService extends IService<AidUserSocial>
{
    /**
     * 查询用户第三方登录授权
     *
     * @param id 用户第三方登录授权主键
     * @return 用户第三方登录授权
     */
    public AidUserSocial selectAidUserSocialById(Long id);

    /**
     * 查询用户第三方登录授权列表
     *
     * @param aidUserSocial 用户第三方登录授权
     * @return 用户第三方登录授权集合
     */
    public List<AidUserSocial> selectAidUserSocialList(AidUserSocial aidUserSocial);

    /**
     * 新增用户第三方登录授权
     *
     * @param aidUserSocial 用户第三方登录授权
     * @return 结果
     */
    public int insertAidUserSocial(AidUserSocial aidUserSocial);

    /**
     * 修改用户第三方登录授权
     *
     * @param aidUserSocial 用户第三方登录授权
     * @return 结果
     */
    public int updateAidUserSocial(AidUserSocial aidUserSocial);

    /**
     * 批量删除用户第三方登录授权
     *
     * @param ids 需要删除的用户第三方登录授权主键集合
     * @return 结果
     */
    public int deleteAidUserSocialByIds(Long[] ids);

    /**
     * 删除用户第三方登录授权信息
     *
     * @param id 用户第三方登录授权主键
     * @return 结果
     */
    public int deleteAidUserSocialById(Long id);
}
