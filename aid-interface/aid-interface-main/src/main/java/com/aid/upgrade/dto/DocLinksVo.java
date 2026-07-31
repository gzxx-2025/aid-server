package com.aid.upgrade.dto;

import java.io.Serializable;

import lombok.Data;

/**
 * 官方教程文档地址集合（随更新清单静默刷新，后台从缓存读取）
 *
 * @author 视觉AID
 */
@Data
public class DocLinksVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 使用教程/文档地址 */
    private String docsUrl;

    /** 提示词开发教程地址 */
    private String promptDocsUrl;

    /** 地址最近一次刷新时间（yyyy-MM-dd HH:mm:ss） */
    private String refreshedAt;
}
