package com.aid.compose.util;

import java.io.UnsupportedEncodingException;

import com.aid.common.utils.file.FileUtils;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletResponse;

/**
 * C 端流式下载附件名工具：ASCII 安全主文件名 + RFC 5987 中文展示名双写。
 * HTTP 响应头仅允许 ASCII，中文文件名只能以百分号编码传输（需客户端解码），
 * 前端直接取头值命名时会得到 {@code %E4%BD%9C...} 乱码字面量。本工具把
 * {@code filename=} 与 {@code download-filename} 统一写为 ASCII 安全名（任何客户端直接可用、
 * 绝不乱码），中文展示名经 {@code filename*=utf-8''} 标准编码保留（支持 RFC 5987 的浏览器
 * 直接下载时仍显示中文名）。
 *
 * @author 视觉AID
 */
public final class AttachmentDownloadNames {

    private AttachmentDownloadNames() {
    }

    /**
     * 名称转 ASCII 安全串：仅保留字母 / 数字 / 短横 / 下划线，其余字符（含中文、空格）移除；
     * 结果为空时回退 fallback。
     *
     * @param rawName  原始名称（可空）
     * @param fallback 转写为空时的回退值（调用方保证为 ASCII）
     * @return ASCII 安全串
     */
    public static String asciiToken(String rawName, String fallback) {
        if (StrUtil.isBlank(rawName)) {
            return fallback;
        }
        String token = rawName.trim().replaceAll("[^A-Za-z0-9_-]", "");
        return StrUtil.isBlank(token) ? fallback : token;
    }

    /**
     * 写附件下载响应头：{@code filename=} / {@code download-filename} 用 ASCII 文件名（直接可用），
     * {@code filename*=utf-8''} 用中文展示名（标准百分号编码）。
     *
     * @param response        HTTP 响应
     * @param asciiFileName   ASCII 安全文件名（含扩展名）
     * @param displayFileName 中文展示文件名（含扩展名）
     * @throws UnsupportedEncodingException 百分号编码异常（UTF-8 恒可用，理论不抛）
     */
    public static void writeAttachmentHeader(HttpServletResponse response, String asciiFileName,
            String displayFileName) throws UnsupportedEncodingException {
        String display = StrUtil.blankToDefault(displayFileName, asciiFileName);
        response.addHeader("Access-Control-Expose-Headers", "Content-Disposition,download-filename");
        response.setHeader("Content-disposition", "attachment; filename=\"" + asciiFileName
                + "\"; filename*=utf-8''" + FileUtils.percentEncode(display));
        response.setHeader("download-filename", asciiFileName);
    }
}
