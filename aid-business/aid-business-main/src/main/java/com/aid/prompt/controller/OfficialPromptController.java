package com.aid.prompt.controller;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.prompt.dto.OfficialPromptItemDetailRequest;
import com.aid.prompt.dto.OfficialPromptItemListRequest;
import com.aid.prompt.service.IOfficialPromptService;
import com.aid.prompt.vo.OfficialPromptCategoryVO;
import com.aid.prompt.vo.OfficialPromptItemVO;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 官方只读参数词库 Controller（C 端只读）
 * 本模块仅提供查询能力，不对外提供任何新增/修改/删除接口；
 * 官方数据由后台管理员在 {@code aid_prompt_lib} 中维护（user_id = 0）。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/prompt/official")
public class OfficialPromptController extends BaseController {

    @Resource
    private IOfficialPromptService officialPromptService;

    /**
     * 查询官方参数词库分类列表
     */
    @PostMapping("/category/list")
    public AjaxResult categoryList() {
        // 仅返回白名单分类，按 sortOrder 升序
        List<OfficialPromptCategoryVO> data = officialPromptService.listCategories();
        return AjaxResult.success(data);
    }

    /**
     * 按分类分页查询官方参数词条列表
     */
    @PostMapping("/item/list")
    public AjaxResult itemList(@RequestBody OfficialPromptItemListRequest request) {
        IPage<OfficialPromptItemVO> page = officialPromptService.listItems(request);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("data", page.getRecords());
        ajax.put("total", page.getTotal());
        ajax.put("pageNum", page.getCurrent());
        ajax.put("pageSize", page.getSize());
        return ajax;
    }

    /**
     * 查询单个官方参数词条详情
     */
    @PostMapping("/item/detail")
    public AjaxResult itemDetail(@Valid @RequestBody OfficialPromptItemDetailRequest request) {
        OfficialPromptItemVO data = officialPromptService.getItemDetail(request);
        return AjaxResult.success(data);
    }
}
