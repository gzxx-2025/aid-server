package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidComicScript;

/**
 * 剧本原文与简化版Service接口
 *
 * @author 视觉AID
 */
public interface IAidComicScriptService extends IService<AidComicScript>
{
    /**
     * 查询剧本原文与简化版
     *
     * @param id 剧本原文与简化版主键
     * @return 剧本原文与简化版
     */
    public AidComicScript selectAidComicScriptById(Long id);

    /**
     * 查询剧本原文与简化版列表
     *
     * @param aidComicScript 剧本原文与简化版
     * @return 剧本原文与简化版集合
     */
    public List<AidComicScript> selectAidComicScriptList(AidComicScript aidComicScript);

    /**
     * 新增剧本原文与简化版
     *
     * @param aidComicScript 剧本原文与简化版
     * @return 结果
     */
    public int insertAidComicScript(AidComicScript aidComicScript);

    /**
     * 修改剧本原文与简化版
     *
     * @param aidComicScript 剧本原文与简化版
     * @return 结果
     */
    public int updateAidComicScript(AidComicScript aidComicScript);

    /**
     * 批量删除剧本原文与简化版
     *
     * @param ids 需要删除的剧本原文与简化版主键集合
     * @return 结果
     */
    public int deleteAidComicScriptByIds(Long[] ids);

    /**
     * 删除剧本原文与简化版信息
     *
     * @param id 剧本原文与简化版主键
     * @return 结果
     */
    public int deleteAidComicScriptById(Long id);
}
