package com.aid.common.moderation.util;

import java.awt.Color;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import lombok.extern.slf4j.Slf4j;

/**
 * 图片归一化工具：把任意来源图片字节统一转换为「24-bit RGB JPEG」字节，规避腾讯云 IMS 等下游审查厂商对。
 *
 * @author 视觉AID
 */
@Slf4j
public final class ImageNormalizer
{
    /**
     * 私有构造，工具类不实例化。
     */
    private ImageNormalizer()
    {
    }

    /**
     * 把任意图片字节归一化为 24-bit RGB JPEG 字节。
     *
     * @param raw 原始图片字节（PNG / JPEG / WEBP / BMP / GIF 等 ImageIO 能识别的格式）
     * @return 归一化后的 JPEG 字节；无法解码时返回原字节
     */
    public static byte[] normalizeToJpeg(byte[] raw)
    {
        // 字节为空直接返回，由调用方处理
        if (Objects.isNull(raw) || raw.length == 0)
        {
            return raw;
        }
        BufferedImage src = decode(raw);
        if (Objects.isNull(src))
        {
            // 解码全失败 → 原字节透传，让下游厂商自己判定
            log.warn("图片归一化失败：ImageIO 与 Toolkit 均无法解码，原字节透传, size={}B", raw.length);
            return raw;
        }
        // 重绘到 24-bit RGB（白底铺底，消除调色板与 alpha）
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try
        {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, src.getWidth(), src.getHeight());
            g.drawImage(src, 0, 0, null);
        }
        finally
        {
            g.dispose();
        }
        try
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, raw.length / 4));
            if (!ImageIO.write(rgb, "jpg", out))
            {
                // ImageIO.write 在没注册 writer 时会返回 false（JDK 自带 JPEG writer 不会，但保留兜底）
                log.warn("图片归一化失败：JPEG writer 未就绪，原字节透传");
                return raw;
            }
            return out.toByteArray();
        }
        catch (Exception e)
        {
            log.warn("图片归一化 JPEG 编码异常：原字节透传, error={}", e.getMessage());
            return raw;
        }
    }

    /**
     * 健壮图片解码：先 ImageIO，失败回退到 AWT Toolkit（对索引色 / 异常 chunk 的 PNG 更宽容）。
     * 解码过程中临时屏蔽 AWT 后台线程向 System.err 的异常 dump，避免污染应用日志。
     *
     * @param bytes 图片字节
     * @return 解码成功的 BufferedImage，全部失败返回 null
     */
    private static BufferedImage decode(byte[] bytes)
    {
        try
        {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (Objects.nonNull(img))
            {
                return img;
            }
        }
        catch (Exception e)
        {
            log.debug("ImageIO 解码失败，准备回退 Toolkit: {}", e.getMessage());
        }
        PrintStream origErr = System.err;
        try
        {
            System.setErr(new PrintStream(new java.io.OutputStream()
            {
                @Override
                public void write(int b)
                {
                    // 故意丢弃 AWT 后台 ImageFetcher 的异常输出
                }
            }));
            Image awt = Toolkit.getDefaultToolkit().createImage(bytes);
            MediaTracker tracker = new MediaTracker(new Container()
            {
                private static final long serialVersionUID = 1L;
            });
            tracker.addImage(awt, 0);
            try
            {
                tracker.waitForAll();
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
            }
            int w = awt.getWidth(null);
            int h = awt.getHeight(null);
            if (w <= 0 || h <= 0)
            {
                return null;
            }
            BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = buf.createGraphics();
            try
            {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, h);
                g.drawImage(awt, 0, 0, null);
            }
            finally
            {
                g.dispose();
            }
            return buf;
        }
        catch (Exception e)
        {
            log.warn("Toolkit 回退解码失败: {}", e.getMessage());
            return null;
        }
        finally
        {
            System.setErr(origErr);
        }
    }
}
