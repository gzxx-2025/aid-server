package com.aid.common.core.page;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.StringUtils;
import com.aid.common.utils.sql.SqlUtil;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页查询实体类（与源 aid-ai PageQuery 行为对齐）
 */
@Data
public class PageQuery implements Serializable {

    public static final int DEFAULT_PAGE_NUM = 1;
    public static final int DEFAULT_PAGE_SIZE = Integer.MAX_VALUE;

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer pageSize;
    private Integer pageNum;
    private String orderByColumn;
    private String isAsc;

    public PageQuery() {
    }

    public PageQuery(Integer pageSize, Integer pageNum) {
        this.pageSize = pageSize;
        this.pageNum = pageNum;
    }

    /**
     * 构建分页对象
     */
    public <T> Page<T> build() {
        Integer pageNum = ObjectUtil.defaultIfNull(getPageNum(), DEFAULT_PAGE_NUM);
        Integer pageSize = ObjectUtil.defaultIfNull(getPageSize(), DEFAULT_PAGE_SIZE);
        if (pageNum <= 0) {
            pageNum = DEFAULT_PAGE_NUM;
        }
        Page<T> page = new Page<>(pageNum, pageSize);
        List<OrderItem> orderItems = buildOrderItem();
        if (CollUtil.isNotEmpty(orderItems)) {
            page.addOrder(orderItems);
        }
        return page;
    }

    private List<OrderItem> buildOrderItem() {
        if (StringUtils.isEmpty(orderByColumn) || StringUtils.isEmpty(isAsc)) {
            return null;
        }
        String orderBy = SqlUtil.escapeOrderBySql(orderByColumn);
        orderBy = StringUtils.toUnderScoreCase(orderBy);

        if ("ascending".equalsIgnoreCase(isAsc)) {
            isAsc = "asc";
        } else if ("descending".equalsIgnoreCase(isAsc)) {
            isAsc = "desc";
        }

        String[] orderByArr = orderBy.split(",");
        String[] isAscArr = isAsc.split(",");
        if (isAscArr.length != 1 && isAscArr.length != orderByArr.length) {
            throw new ServiceException("排序参数有误");
        }

        List<OrderItem> list = new ArrayList<>();
        for (int i = 0; i < orderByArr.length; i++) {
            String orderByStr = orderByArr[i];
            String isAscStr = isAscArr.length == 1 ? isAscArr[0] : isAscArr[i];
            if ("asc".equals(isAscStr)) {
                list.add(OrderItem.asc(orderByStr));
            } else if ("desc".equals(isAscStr)) {
                list.add(OrderItem.desc(orderByStr));
            } else {
                throw new ServiceException("排序参数有误");
            }
        }
        return list;
    }

    @JsonIgnore
    public Integer getFirstNum() {
        int pn = ObjectUtil.defaultIfNull(pageNum, DEFAULT_PAGE_NUM);
        int ps = ObjectUtil.defaultIfNull(pageSize, DEFAULT_PAGE_SIZE);
        return (pn - 1) * ps;
    }
}
