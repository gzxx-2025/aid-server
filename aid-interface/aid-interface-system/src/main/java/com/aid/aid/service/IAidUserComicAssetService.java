package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidUserComicAsset;

/**
 * 用户自定义漫画参考资产Service接口
 *
 * @author 视觉AID
 */
public interface IAidUserComicAssetService extends IService<AidUserComicAsset>
{
    /**
     * 查询用户自定义漫画参考资产
     *
     * @param id 用户自定义漫画参考资产主键
     * @return 用户自定义漫画参考资产
     */
    public AidUserComicAsset selectAidUserComicAssetById(Long id);

    /**
     * 查询用户自定义漫画参考资产列表
     *
     * @param aidUserComicAsset 用户自定义漫画参考资产
     * @return 用户自定义漫画参考资产集合
     */
    public List<AidUserComicAsset> selectAidUserComicAssetList(AidUserComicAsset aidUserComicAsset);

    /**
     * 新增用户自定义漫画参考资产
     *
     * @param aidUserComicAsset 用户自定义漫画参考资产
     * @return 结果
     */
    public int insertAidUserComicAsset(AidUserComicAsset aidUserComicAsset);

    /**
     * 修改用户自定义漫画参考资产
     *
     * @param aidUserComicAsset 用户自定义漫画参考资产
     * @return 结果
     */
    public int updateAidUserComicAsset(AidUserComicAsset aidUserComicAsset);

    /**
     * 批量删除用户自定义漫画参考资产
     *
     * @param ids 需要删除的用户自定义漫画参考资产主键集合
     * @return 结果
     */
    public int deleteAidUserComicAssetByIds(Long[] ids);

    /**
     * 删除用户自定义漫画参考资产信息
     *
     * @param id 用户自定义漫画参考资产主键
     * @return 结果
     */
    public int deleteAidUserComicAssetById(Long id);
}
