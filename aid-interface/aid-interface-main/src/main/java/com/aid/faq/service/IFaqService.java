package com.aid.faq.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.faq.dto.FaqDetailRequest;
import com.aid.faq.dto.FaqListRequest;
import com.aid.faq.vo.FaqDetailVO;
import com.aid.faq.vo.FaqListItemVO;

/**
 * 常见问题 - C 端只读 Service 接口
 *
 * @author 视觉AID
 */
public interface IFaqService
{
    /**
     * 分页查询常见问题列表（仅显示状态，不含完整内容）
     *
     * @param request 查询请求
     * @return 分页结果
     */
    IPage<FaqListItemVO> listFaqs(FaqListRequest request);

    /**
     * 查询常见问题详情（含完整内容），同时累加浏览次数
     *
     * @param request 详情请求
     * @return 详情
     */
    FaqDetailVO getFaqDetail(FaqDetailRequest request);
}
