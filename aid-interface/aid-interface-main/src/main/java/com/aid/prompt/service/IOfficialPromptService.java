package com.aid.prompt.service;

import java.util.List;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.prompt.dto.OfficialPromptItemDetailRequest;
import com.aid.prompt.dto.OfficialPromptItemListRequest;
import com.aid.prompt.vo.OfficialPromptCategoryVO;
import com.aid.prompt.vo.OfficialPromptItemVO;

/**
 * 官方只读参数词库 Service
 * 仅官方只读：user_id = 0 && status = '0' && del_flag = '0'，
 * 分类必须命中 {@link com.aid.prompt.constant.OfficialPromptCategory} 白名单。
 * 所有接口不涉及新增、修改、删除。
 *
 * @author 视觉AID
 */
public interface IOfficialPromptService {

    /**
     * 查询官方参数词库分类列表（含分类下词条数量）
     *
     * @return 分类 VO 列表（已按 sortOrder 升序）
     */
    List<OfficialPromptCategoryVO> listCategories();

    /**
     * 按分类分页查询官方参数词条列表
     *
     * @param request 查询入参（含 pageNum/pageSize）
     * @return 词条 VO 分页结果
     */
    IPage<OfficialPromptItemVO> listItems(OfficialPromptItemListRequest request);

    /**
     * 查询单个官方参数词条详情
     *
     * @param request 查询入参
     * @return 词条 VO
     */
    OfficialPromptItemVO getItemDetail(OfficialPromptItemDetailRequest request);
}
