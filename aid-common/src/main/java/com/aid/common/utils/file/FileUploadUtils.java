package com.aid.common.utils.file;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.io.FilenameUtils;
import org.springframework.web.multipart.MultipartFile;
import com.aid.common.config.AidAppConfig;
import com.aid.common.constant.Constants;
import com.aid.common.exception.file.FileNameLengthLimitExceededException;
import com.aid.common.exception.file.FileSizeLimitExceededException;
import com.aid.common.exception.file.InvalidExtensionException;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.StringUtils;
import com.aid.common.utils.uuid.IdUtils;
import com.aid.common.utils.uuid.Seq;

/**
 * 文件上传工具类
 *
 * @author 视觉AID
 */
public class FileUploadUtils
{
    /**
     * 默认大小 50M
     */
    public static final long DEFAULT_MAX_SIZE = 50 * 1024 * 1024L;

    /**
     * 默认的文件名最大长度 100
     */
    public static final int DEFAULT_FILE_NAME_LENGTH = 100;

    /**
     * 默认上传的地址
     */
    private static String defaultBaseDir = AidAppConfig.getProfile();

    public static void setDefaultBaseDir(String defaultBaseDir)
    {
        FileUploadUtils.defaultBaseDir = defaultBaseDir;
    }

    public static String getDefaultBaseDir()
    {
        return defaultBaseDir;
    }

    /**
     * 以默认配置进行文件上传
     *
     * @param file 上传的文件
     * @return 文件名称
     * @throws Exception
     */
    public static final String upload(MultipartFile file) throws IOException
    {
        try
        {
            return upload(getDefaultBaseDir(), file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION);
        }
        catch (Exception e)
        {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 根据文件路径上传
     *
     * @param baseDir 相对应用的基目录
     * @param file 上传的文件
     * @return 文件名称
     * @throws IOException
     */
    public static final String upload(String baseDir, MultipartFile file) throws IOException
    {
        try
        {
            return upload(baseDir, file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION);
        }
        catch (Exception e)
        {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 文件上传。
     *
     * @param baseDir 相对应用的基目录
     * @param file 上传的文件
     * @param allowedExtension 上传文件类型
     * @return 返回上传成功的文件名
     * @throws FileSizeLimitExceededException 如果超出最大大小
     * @throws FileNameLengthLimitExceededException 文件名太长
     * @throws IOException 比如读写文件出错时
     * @throws InvalidExtensionException 文件校验异常
     */
    public static final String upload(String baseDir, MultipartFile file, String[] allowedExtension)
            throws FileSizeLimitExceededException, IOException, FileNameLengthLimitExceededException,
            InvalidExtensionException
    {
        return upload(baseDir, file, allowedExtension, false);
    }

    /**
     * 文件上传。
     *
     * @param baseDir 相对应用的基目录
     * @param file 上传的文件
     * @param useCustomNaming 系统自定义文件名
     * @param allowedExtension 上传文件类型
     * @return 返回上传成功的文件名
     * @throws FileSizeLimitExceededException 如果超出最大大小
     * @throws FileNameLengthLimitExceededException 文件名太长
     * @throws IOException 比如读写文件出错时
     * @throws InvalidExtensionException 文件校验异常
     */
    public static final String upload(String baseDir, MultipartFile file, String[] allowedExtension, boolean useCustomNaming)
            throws FileSizeLimitExceededException, IOException, FileNameLengthLimitExceededException,
            InvalidExtensionException
    {
        int fileNameLength = Objects.requireNonNull(file.getOriginalFilename()).length();
        if (fileNameLength > FileUploadUtils.DEFAULT_FILE_NAME_LENGTH)
        {
            throw new FileNameLengthLimitExceededException(FileUploadUtils.DEFAULT_FILE_NAME_LENGTH);
        }

        assertAllowed(file, allowedExtension);

        String fileName = useCustomNaming ? uuidFilename(file) : extractFilename(file);

        String absPath = getAbsoluteFile(baseDir, fileName).getAbsolutePath();
        file.transferTo(Paths.get(absPath));
        return getPathFileName(baseDir, fileName);
    }

    /**
     * 编码文件名(日期格式目录 + 原文件名 + 序列值 + 后缀)
     */
    public static final String extractFilename(MultipartFile file)
    {
        return StringUtils.format("{}/{}_{}.{}", DateUtils.datePath(), FilenameUtils.getBaseName(file.getOriginalFilename()), Seq.getId(Seq.uploadSeqType), getExtension(file));
    }

    /**
     * 编编码文件名(日期格式目录 + UUID + 后缀)
     */
    public static final String uuidFilename(MultipartFile file)
    {
        return StringUtils.format("{}/{}.{}", DateUtils.datePath(), IdUtils.fastSimpleUUID(), getExtension(file));
    }

    public static final File getAbsoluteFile(String uploadDir, String fileName) throws IOException
    {
        File desc = new File(uploadDir + File.separator + fileName);

        if (!desc.exists())
        {
            if (!desc.getParentFile().exists())
            {
                desc.getParentFile().mkdirs();
            }
        }
        return desc;
    }

    public static final String getPathFileName(String uploadDir, String fileName) throws IOException
    {
        int dirLastIndex = AidAppConfig.getProfile().length() + 1;
        String currentDir = StringUtils.substring(uploadDir, dirLastIndex);
        return Constants.RESOURCE_PREFIX + "/" + currentDir + "/" + fileName;
    }

    /**
     * 文件大小校验
     *
     * @param file 上传的文件
     * @return
     * @throws FileSizeLimitExceededException 如果超出最大大小
     * @throws InvalidExtensionException
     */
    public static final void assertAllowed(MultipartFile file, String[] allowedExtension)
            throws FileSizeLimitExceededException, InvalidExtensionException
    {
        long size = file.getSize();
        if (size > DEFAULT_MAX_SIZE)
        {
            throw new FileSizeLimitExceededException(DEFAULT_MAX_SIZE / 1024 / 1024);
        }

        String fileName = file.getOriginalFilename();
        String extension = getExtension(file);
        if (allowedExtension != null && !isAllowedExtension(extension, allowedExtension))
        {
            if (allowedExtension == MimeTypeUtils.IMAGE_EXTENSION)
            {
                throw new InvalidExtensionException.InvalidImageExtensionException(allowedExtension, extension,
                        fileName);
            }
            else if (allowedExtension == MimeTypeUtils.FLASH_EXTENSION)
            {
                throw new InvalidExtensionException.InvalidFlashExtensionException(allowedExtension, extension,
                        fileName);
            }
            else if (allowedExtension == MimeTypeUtils.MEDIA_EXTENSION)
            {
                throw new InvalidExtensionException.InvalidMediaExtensionException(allowedExtension, extension,
                        fileName);
            }
            else if (allowedExtension == MimeTypeUtils.VIDEO_EXTENSION)
            {
                throw new InvalidExtensionException.InvalidVideoExtensionException(allowedExtension, extension,
                        fileName);
            }
            else
            {
                throw new InvalidExtensionException(allowedExtension, extension, fileName);
            }
        }

        // 对可识别签名的常见类型做 magic bytes 二次校验，防止 .png 实际是 HTML/JS
        assertMagicBytesMatch(file, extension, fileName);
    }

    /**
     * magic bytes 签名表。
     * 只覆盖"能通过签名唯一识别"的类型；Office/压缩等复杂容器不强校（避免误伤）。
     */
    private static final Map<String, byte[][]> MAGIC_BYTES = buildMagicBytesMap();

    private static Map<String, byte[][]> buildMagicBytesMap()
    {
        Map<String, byte[][]> map = new HashMap<>();
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        map.put("png", new byte[][] { new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A } });
        // JPG/JPEG: FF D8 FF
        byte[] jpg = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
        map.put("jpg", new byte[][] { jpg });
        map.put("jpeg", new byte[][] { jpg });
        // GIF: 47 49 46 38 (37|39) 61 （GIF87a / GIF89a）
        map.put("gif", new byte[][] {
                new byte[] { 0x47, 0x49, 0x46, 0x38, 0x37, 0x61 },
                new byte[] { 0x47, 0x49, 0x46, 0x38, 0x39, 0x61 }
        });
        // BMP: 42 4D
        map.put("bmp", new byte[][] { new byte[] { 0x42, 0x4D } });
        // WEBP: 首 4 字节 RIFF，8-11 字节 WEBP（这里只校 RIFF，后续按需扩展）
        map.put("webp", new byte[][] { new byte[] { 0x52, 0x49, 0x46, 0x46 } });
        // PDF: 25 50 44 46 2D (%PDF-)
        map.put("pdf", new byte[][] { new byte[] { 0x25, 0x50, 0x44, 0x46, 0x2D } });
        // MP4: 前 4 字节是 box size，第 5~8 字节是 "ftyp"
        // 这里校验 4~7 字节是否 "ftyp"
        return map;
    }

    private static final List<String> NEEDS_MAGIC_CHECK = Arrays.asList(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "pdf"
    );

    private static void assertMagicBytesMatch(MultipartFile file, String extension, String fileName)
            throws InvalidExtensionException
    {
        if (extension == null)
        {
            return;
        }
        String ext = extension.toLowerCase();
        if (!NEEDS_MAGIC_CHECK.contains(ext))
        {
            return;
        }
        byte[][] expected = MAGIC_BYTES.get(ext);
        if (expected == null || expected.length == 0)
        {
            return;
        }
        byte[] head;
        try (InputStream is = file.getInputStream())
        {
            head = is.readNBytes(16);
        }
        catch (IOException e)
        {
            throw new InvalidExtensionException(new String[] { ext }, ext, fileName);
        }
        for (byte[] sig : expected)
        {
            if (head.length >= sig.length)
            {
                boolean match = true;
                for (int i = 0; i < sig.length; i++)
                {
                    if (head[i] != sig[i])
                    {
                        match = false;
                        break;
                    }
                }
                if (match)
                {
                    return;
                }
            }
        }
        // magic bytes 不匹配，认为扩展名伪造
        throw new InvalidExtensionException(new String[] { ext }, ext, fileName);
    }

    /**
     * 判断MIME类型是否是允许的MIME类型
     *
     * @param extension
     * @param allowedExtension
     * @return
     */
    public static final boolean isAllowedExtension(String extension, String[] allowedExtension)
    {
        for (String str : allowedExtension)
        {
            if (str.equalsIgnoreCase(extension))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取文件名的后缀
     *
     * @param file 表单文件
     * @return 后缀名
     */
    public static final String getExtension(MultipartFile file)
    {
        String extension = FilenameUtils.getExtension(file.getOriginalFilename());
        if (StringUtils.isEmpty(extension))
        {
            extension = MimeTypeUtils.getExtension(Objects.requireNonNull(file.getContentType()));
        }
        return extension;
    }
}
