package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidComicAsset;

/**
 * 项目提取资产Service接口
 *
 * @author 视觉AID
 */
public interface IAidComicAssetService extends IService<AidComicAsset>
{
    /**
     * 查询项目提取资产
     *
     * @param id 项目提取资产主键
     * @return 项目提取资产
     */
    public AidComicAsset selectAidComicAssetById(Long id);

    /**
     * 查询项目提取资产列表
     *
     * @param aidComicAsset 项目提取资产
     * @return 项目提取资产集合
     */
    public List<AidComicAsset> selectAidComicAssetList(AidComicAsset aidComicAsset);

    /**
     * 新增项目提取资产
     *
     * @param aidComicAsset 项目提取资产
     * @return 结果
     */
    public int insertAidComicAsset(AidComicAsset aidComicAsset);

    /**
     * 修改项目提取资产
     *
     * @param aidComicAsset 项目提取资产
     * @return 结果
     */
    public int updateAidComicAsset(AidComicAsset aidComicAsset);

    /**
     * 批量删除项目提取资产
     *
     * @param ids 需要删除的项目提取资产主键集合
     * @return 结果
     */
    public int deleteAidComicAssetByIds(Long[] ids);

    /**
     * 删除项目提取资产信息
     *
     * @param id 项目提取资产主键
     * @return 结果
     */
    public int deleteAidComicAssetById(Long id);
}
