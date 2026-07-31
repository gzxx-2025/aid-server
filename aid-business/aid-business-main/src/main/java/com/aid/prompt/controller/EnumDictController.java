package com.aid.prompt.controller;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.prompt.dto.EnumDictListRequest;
import com.aid.prompt.service.IEnumDictService;
import com.aid.prompt.vo.EnumDictGroupVO;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * 枚举字典 Controller（C 端只读）
 * 与数据库词库完全分离，不允许与 aid_prompt_lib 混查。
 * 仅允许白名单内的枚举类型。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/dict")
public class EnumDictController extends BaseController {

    @Resource
    private IEnumDictService enumDictService;

    /**
     * 批量查询枚举字典
     */
    @PostMapping("/enum/list")
    public AjaxResult enumList(@Valid @RequestBody EnumDictListRequest request) {
        List<EnumDictGroupVO> data = enumDictService.listEnums(request);
        return AjaxResult.success(data);
    }
}
