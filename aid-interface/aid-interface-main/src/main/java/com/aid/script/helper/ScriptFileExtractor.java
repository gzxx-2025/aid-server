package com.aid.script.helper;

import java.nio.charset.StandardCharsets;

import org.springframework.web.multipart.MultipartFile;

import com.aid.common.exception.ServiceException;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 剧本文件正文提取工具
 * 仅支持纯文本（.txt）一种格式，负责把上传的文件解析为纯文本正文，
 * 并拦截"图片等二进制文件改后缀伪装成 .txt"的情况。
 * 不做任何业务校验（字数 / HTML / 归属），这些由 Service 层处理，本类只负责"取出文字"。
 *
 * @author 视觉AID
 */
@Slf4j
public final class ScriptFileExtractor
{
    /** 扩展名：纯文本 */
    public static final String EXT_TXT = "txt";

    /** UTF-8 BOM 字符（部分 txt 文件开头会带，需剔除） */
    private static final char UTF8_BOM = '\uFEFF';

    /** 二进制嗅探的最大扫描字节数（二进制特征基本都在文件头部，扫描前 8KB 足够） */
    private static final int BINARY_SNIFF_LENGTH = 8192;

    /**
     * 常见二进制文件魔数（文件头签名），命中即判定为非文本文件。
     * 覆盖：JPEG、PNG、GIF、ZIP（docx/xlsx/压缩包）、OLE2（旧版 doc/xls）、PDF。
     * 仅收录含控制字节或完整特征串的签名，避免误伤正常文本开头；
     * BMP / WebP / 音视频等其余二进制由下方 NUL 字节检测兜底。
     */
    private static final byte[][] BINARY_MAGIC_HEADERS = {
            {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},                                                    // JPEG
            {(byte) 0x89, 'P', 'N', 'G'},                                                               // PNG
            {'G', 'I', 'F', '8', '7', 'a'},                                                             // GIF87a
            {'G', 'I', 'F', '8', '9', 'a'},                                                             // GIF89a
            {'P', 'K', 0x03, 0x04},                                                                     // ZIP（docx/xlsx 等）
            {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}, // OLE2（旧版 doc/xls）
            {'%', 'P', 'D', 'F', '-'},                                                                  // PDF
    };

    /** 工具类禁止实例化 */
    private ScriptFileExtractor()
    {
    }

    /**
     * 根据扩展名解析文件正文
     *
     * @param file 上传文件
     * @param ext  小写扩展名（仅支持 txt）
     * @return 解析出的纯文本正文
     */
    public static String extractText(MultipartFile file, String ext)
    {
        try
        {
            // 按扩展名分发（当前仅允许 txt，其余扩展名已在 Service 层白名单拦截）
            if (EXT_TXT.equals(ext))
            {
                return extractTxt(file);
            }
            // 理论上不会走到这里（扩展名已在 Service 层白名单校验）
            log.error("剧本文件解析失败：不支持的扩展名, ext={}", ext);
            throw new ServiceException("格式不支持");
        }
        catch (ServiceException e)
        {
            // 业务异常原样抛出，保留短文案
            throw e;
        }
        catch (Exception e)
        {
            // 解析类异常（文件损坏 / 读取失败等）统一打日志再抛短文案
            log.error("剧本文件解析失败, fileName={}", file.getOriginalFilename(), e);
            throw new ServiceException("解析失败");
        }
    }

    /**
     * 解析 .txt：先做二进制内容嗅探（拦截改后缀伪装成 .txt 的图片等文件），
     * 再按 UTF-8 读取并剔除可能存在的 BOM
     */
    private static String extractTxt(MultipartFile file) throws Exception
    {
        byte[] bytes = file.getBytes();
        // 拦截"图片等二进制文件改后缀为 .txt"的情况
        assertNotBinary(bytes, file.getOriginalFilename());
        String text = new String(bytes, StandardCharsets.UTF_8);
        // 剔除开头 UTF-8 BOM，避免计数与展示出现不可见字符
        if (StrUtil.isNotEmpty(text) && text.charAt(0) == UTF8_BOM)
        {
            text = text.substring(1);
        }
        return text;
    }

    /**
     * 二进制内容嗅探：命中已知二进制魔数、或头部含 NUL 字节即拒绝。
     * 纯文本（UTF-8 / GBK 等常见编码）不会出现 NUL 字节；图片、Office、压缩包等二进制文件
     * 头部几乎必然命中魔数或含 NUL，两者结合可有效拦截改后缀伪装的非文本文件。
     *
     * @param bytes    文件字节
     * @param fileName 原始文件名（仅用于日志）
     */
    private static void assertNotBinary(byte[] bytes, String fileName)
    {
        if (bytes == null || bytes.length == 0)
        {
            return;
        }
        // 魔数匹配：JPEG / PNG / GIF / ZIP(docx) / OLE2(doc) / PDF
        for (byte[] magic : BINARY_MAGIC_HEADERS)
        {
            if (startsWith(bytes, magic))
            {
                log.error("剧本文件校验失败：命中二进制魔数, fileName={}", fileName);
                throw new ServiceException("内容非文本");
            }
        }
        // NUL 字节检测：兜底拦截 BMP / WebP / 音视频等其余二进制格式
        int sniffLength = Math.min(bytes.length, BINARY_SNIFF_LENGTH);
        for (int i = 0; i < sniffLength; i++)
        {
            if (bytes[i] == 0)
            {
                log.error("剧本文件校验失败：内容含NUL字节, fileName={}", fileName);
                throw new ServiceException("内容非文本");
            }
        }
    }

    /**
     * 判断字节数组是否以指定前缀开头
     */
    private static boolean startsWith(byte[] bytes, byte[] prefix)
    {
        if (bytes.length < prefix.length)
        {
            return false;
        }
        for (int i = 0; i < prefix.length; i++)
        {
            if (bytes[i] != prefix[i])
            {
                return false;
            }
        }
        return true;
    }
}
