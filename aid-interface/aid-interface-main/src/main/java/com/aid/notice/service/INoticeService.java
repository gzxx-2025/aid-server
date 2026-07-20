package com.aid.notice.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.aid.notice.dto.NoticeDetailRequest;
import com.aid.notice.dto.NoticeListRequest;
import com.aid.notice.vo.NoticeDetailVO;
import com.aid.notice.vo.NoticeListItemVO;

/**
 * 公告 - C 端只读 Service 接口
 *
 * @author 视觉AID
 */
public interface INoticeService
{
    /**
     * 分页查询当前可展示的公告列表
     * 仅返回 status=0（显示）且处于生效时间区间内的记录，置顶优先、sortOrder 升序。
     *
     * @param request 查询请求（pageNum/pageSize/noticeType/keyword 可选）
     * @return 公告分页结果（列表项不含完整内容）
     */
    IPage<NoticeListItemVO> listNotices(NoticeListRequest request);

    /**
     * 查询公告详情（含完整内容），命中后累加浏览次数
     *
     * @param request 详情请求（id 必填）
     * @return 公告详情
     */
    NoticeDetailVO getNoticeDetail(NoticeDetailRequest request);
}
