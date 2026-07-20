package com.aid.prompt.service;

import java.util.List;

import com.aid.prompt.dto.EnumDictListRequest;
import com.aid.prompt.vo.EnumDictGroupVO;

/**
 * 枚举字典 Service（与数据库词库完全分离）
 *
 * @author 视觉AID
 */
public interface IEnumDictService {

    /**
     * 批量查询枚举字典
     *
     * @param request 查询入参（enumTypes 必须来自白名单）
     * @return 分组后的枚举数据
     */
    List<EnumDictGroupVO> listEnums(EnumDictListRequest request);
}
