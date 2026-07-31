package com.aid.common.page;

import java.util.Objects;

import com.github.pagehelper.PageHelper;
import com.aid.common.core.page.PageDomain;
import com.aid.common.core.page.TableSupport;

/**
 * C 端安全分页工具：从请求上下文取分页参数并钳制上限后开启 PageHelper 分页。
 * 供 Service 在归属校验之后、列表查询之前调用，保证分页精确作用于列表查询本身。
 *
 * @author 视觉AID
 */
public final class SafePageUtils {

    private SafePageUtils() {
    }

    /** 默认页码 */
    private static final int DEFAULT_PAGE_NUM = 1;

    /** 默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** 每页条数上限（防前端乱传超大 pageSize 拉爆内存） */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 开启钳制分页：pageNum 最小 1，pageSize 钳制在 [1, 100]，缺省 10。
     * 必须紧邻目标列表查询调用（PageHelper 只拦截下一条 SQL）。
     */
    public static void startClampedPage() {
        PageDomain pageDomain = TableSupport.buildPageRequest();
        Integer rawPageNum = pageDomain.getPageNum();
        Integer rawPageSize = pageDomain.getPageSize();
        int pageNum = (Objects.isNull(rawPageNum) || rawPageNum < 1) ? DEFAULT_PAGE_NUM : rawPageNum;
        int pageSize = Objects.isNull(rawPageSize) ? DEFAULT_PAGE_SIZE
                : Math.min(Math.max(rawPageSize, 1), MAX_PAGE_SIZE);
        PageHelper.startPage(pageNum, pageSize);
    }
}
