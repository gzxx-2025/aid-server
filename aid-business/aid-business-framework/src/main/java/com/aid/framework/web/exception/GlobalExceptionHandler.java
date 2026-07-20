package com.aid.framework.web.exception;

import com.aid.common.constant.HttpStatus;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.text.Convert;
import com.aid.common.aid.crypto.exception.ApiCryptoException;
import com.aid.common.exception.DemoModeException;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.MessageUtils;
import com.aid.common.utils.StringUtils;
import com.aid.common.utils.html.EscapeUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器
 * 
 * @author AID
 */
@RestControllerAdvice
public class GlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 权限校验异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',权限校验失败'{}'", requestURI, e.getMessage());
        return AjaxResult.error(HttpStatus.FORBIDDEN, MessageUtils.message("error.forbidden"));
    }

    /**
     * 请求方式不支持
     * 明确告知前端调用方式错误（如用 GET 调了 POST 接口），返回 405 便于前端定位，而非兜底"系统繁忙"。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public AjaxResult handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e,
            HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.warn("请求地址'{}',不支持'{}'请求", requestURI, e.getMethod());
        return AjaxResult.error(HttpStatus.BAD_METHOD, MessageUtils.message("error.method.not.supported"));
    }

    /**
     * 接口不存在（404）
     * Spring Boot 3 下访问未映射的路径会抛 NoResourceFoundException / NoHandlerFoundException，
     * 默认会被兜底 handleException 包装成"系统繁忙"，导致前端无法判断是自己调错了接口地址。
     * 这里单独拦截，明确返回 404 + "接口不存在"，便于前端快速定位调用问题。
     */
    @ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
    public AjaxResult handleNotFoundException(Exception e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.warn("请求地址'{}'不存在，返回接口不存在", requestURI);
        return AjaxResult.error(HttpStatus.NOT_FOUND, MessageUtils.message("error.not.found"));
    }

    /**
     * 请求体格式错误（如 JSON 解析失败、请求体为空）
     * 属于明确的调用方错误，需明确返回 400 而非兜底"系统繁忙"，避免前端误以为是后端故障。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.warn("请求地址'{}',请求体格式错误: {}", requestURI, e.getMessage());
        return AjaxResult.error(HttpStatus.BAD_REQUEST, MessageUtils.message("error.request.body.invalid"));
    }

    /**
     * 缺少必要请求参数
     * 明确告知前端漏传了哪个参数，返回 400，而非兜底"系统繁忙"。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public AjaxResult handleMissingServletRequestParameter(MissingServletRequestParameterException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.warn("请求地址'{}',缺少必要参数[{}]", requestURI, e.getParameterName());
        return AjaxResult.error(HttpStatus.BAD_REQUEST, MessageUtils.message("error.missing.parameter"));
    }

    /**
     * 请求媒体类型不支持（Content-Type 不匹配，如接口要求 JSON 却传了 form）
     * 属于明确的调用方错误，返回 415 而非兜底"系统繁忙"。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public AjaxResult handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.warn("请求地址'{}',不支持的媒体类型[{}]", requestURI, e.getContentType());
        return AjaxResult.error(HttpStatus.UNSUPPORTED_TYPE, MessageUtils.message("error.media.type.not.supported"));
    }

    /**
     * 业务异常
     */
    @ExceptionHandler(ServiceException.class)
    public AjaxResult handleServiceException(ServiceException e, HttpServletRequest request)
    {
        log.error(e.getMessage(), e);
        Integer code = e.getCode();
        return StringUtils.isNotNull(code) ? AjaxResult.error(code, e.getMessage()) : AjaxResult.error(e.getMessage());
    }

    /**
     * 请求路径中缺少必需的路径变量
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public AjaxResult handleMissingPathVariableException(MissingPathVariableException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求路径中缺少必需的路径变量'{}',发生系统异常.", requestURI, e);
        return AjaxResult.error(MessageUtils.message("error.missing.path.variable"));
    }

    /**
     * 请求参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public AjaxResult handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        String value = Convert.toStr(e.getValue());
        if (StringUtils.isNotEmpty(value))
        {
            value = EscapeUtil.clean(value);
        }
        log.error("请求参数类型不匹配'{}',参数[{}]要求类型为'{}',输入值为'{}',发生系统异常.", requestURI, e.getName(), e.getRequiredType().getName(), value, e);
        return AjaxResult.error(MessageUtils.message("error.argument.type.mismatch"));
    }

    /**
     * 拦截未知的运行时异常
     * <p>
     * 业务代码约定：抛出的异常 message 均为「已美化的短文案」（如"模型异常""字数超限"），且抛出前已 log 记录，
     * 这类文案本就是要展示给用户的。此前一律兜底"系统繁忙"，导致用户看不到真正原因、排障困难。
     * </p>
     * <p>
     * 现改为：message 通过 {@link #isSafeBusinessMessage(String)} 安全校验（单行、短文案、不含类名/SQL/堆栈/引号/null
     * 等技术敏感标记）时直接透传给前端；否则仍统一返回"系统繁忙"，防止 SQL 片段 / 字段名 / 堆栈摘要泄漏。
     * </p>
     */
    @ExceptionHandler(RuntimeException.class)
    public AjaxResult handleRuntimeException(RuntimeException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        // 客户端主动断开（SSE/下载流 Broken pipe / ClientAbort / 异步超时）不是服务端错误，
        // 响应已 committed 无法再写 body，静默 info 收尾，避免刷错误堆栈 + 二次 HttpMessageNotWritableException。
        if (isClientAbort(e))
        {
            log.info("请求地址'{}'客户端已断开连接，忽略", requestURI);
            return null;
        }
        log.error("请求地址'{}',发生未知异常.", requestURI, e);
        // 安全短文案直接透传，让用户看到真实原因；技术性/超长/含敏感标记的 message 兜底"系统繁忙"
        String message = e.getMessage();
        if (isSafeBusinessMessage(message))
        {
            return AjaxResult.error(message);
        }
        return AjaxResult.error(MessageUtils.message("error.system.busy"));
    }

    /**
     * 系统异常
     */
    @ExceptionHandler(Exception.class)
    public AjaxResult handleException(Exception e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        // 同上，客户端断开静默处理
        if (isClientAbort(e))
        {
            log.info("请求地址'{}'客户端已断开连接，忽略", requestURI);
            return null;
        }
        log.error("请求地址'{}',发生系统异常.", requestURI, e);
        // 同 RuntimeException：安全短文案透传，其余兜底"系统繁忙"
        String message = e.getMessage();
        if (isSafeBusinessMessage(message))
        {
            return AjaxResult.error(message);
        }
        return AjaxResult.error(MessageUtils.message("error.system.busy"));
    }

    /**
     * 业务异常短文案安全上限（字符数）。超过视为技术性/堆栈信息，不透传。
     */
    private static final int SAFE_MESSAGE_MAX_LENGTH = 50;

    /**
     * 技术/敏感关键词黑名单（小写匹配）。命中任一即判定为非业务短文案，不透传给前端。
     */
    private static final String[] UNSAFE_MESSAGE_TOKENS = {
            "exception", "null", "###", "java.", "com.", "org.", "sun.", "jakarta.",
            "springframework", "sql", "select ", "insert ", "update ", "delete ", "from ",
            "\tat ", "nested", "caused by", "http", "0x"
    };

    /**
     * 判断异常 message 是否为「可安全透传给前端的业务短文案」。
     * <p>
     * 通过条件（全部满足）：非空白、单行（不含换行/制表符）、长度不超过 {@link #SAFE_MESSAGE_MAX_LENGTH}、
     * 不含任何 {@link #UNSAFE_MESSAGE_TOKENS} 技术关键词、不含双引号、不含「英文类名/包名」形态（形如 a.b）。
     * </p>
     *
     * @param message 异常 message
     * @return true=安全短文案可透传；false=兜底"系统繁忙"
     */
    private boolean isSafeBusinessMessage(String message)
    {
        // 1) 空白直接兜底
        if (StringUtils.isBlank(message))
        {
            return false;
        }
        // 2) 长度超限视为技术性信息，兜底
        if (message.length() > SAFE_MESSAGE_MAX_LENGTH)
        {
            return false;
        }
        // 3) 含换行/制表符（堆栈多行）或双引号（NPE 帮助信息含方法签名）兜底
        if (message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0
                || message.indexOf('\t') >= 0 || message.indexOf('"') >= 0)
        {
            return false;
        }
        String lower = message.toLowerCase();
        // 4) 命中技术关键词黑名单兜底
        for (String token : UNSAFE_MESSAGE_TOKENS)
        {
            if (lower.contains(token))
            {
                return false;
            }
        }
        // 5) 形如「英文.英文」的类名/包名/方法引用兜底（业务短文案不会出现该形态）
        if (lower.matches(".*[a-z]\\.[a-z].*"))
        {
            return false;
        }
        return true;
    }

    /**
     * 判断异常链是否为"客户端主动断开连接"（Broken pipe / ClientAbortException /
     * AsyncRequestNotUsableException / 连接重置 / 异步超时等）。
     * <p>这类异常常见于 SSE 长连接、文件下载流：客户端关页面/刷新/网络中断导致服务端写出失败，
     * 属正常连接收尾，不应按服务端错误记录 ERROR 堆栈。</p>
     */
    private boolean isClientAbort(Throwable e)
    {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 10)
        {
            String cn = cur.getClass().getName();
            if (cn.contains("ClientAbortException")
                    || cn.contains("AsyncRequestNotUsableException")
                    || cn.contains("AsyncRequestTimeoutException"))
            {
                return true;
            }
            String msg = cur.getMessage();
            if (StringUtils.isNotEmpty(msg))
            {
                String lower = msg.toLowerCase();
                if (lower.contains("broken pipe")
                        || msg.contains("断开的管道")
                        || lower.contains("connection reset"))
                {
                    return true;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(BindException.class)
    public AjaxResult handleBindException(BindException e)
    {
        log.error(e.getMessage(), e);
        String message = e.getAllErrors().get(0).getDefaultMessage();
        return AjaxResult.error(message);
    }

    /**
     * 参数校验异常：返回首个字段校验失败文案，fieldErrors 为空时回退通用文案。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleMethodArgumentNotValidException(MethodArgumentNotValidException e)
    {
        log.error(e.getMessage(), e);
        if (e.getBindingResult() == null || e.getBindingResult().getFieldError() == null)
        {
            return AjaxResult.error(MessageUtils.message("error.validation"));
        }
        String message = e.getBindingResult().getFieldError().getDefaultMessage();
        if (StringUtils.isEmpty(message))
        {
            message = MessageUtils.message("error.validation");
        }
        return AjaxResult.error(message);
    }

    /**
     * 接口加解密异常。
     * 信封加密链路（密钥缺失/IV非法/时间戳过期/AES-RSA 失败等）统一在此兜底：
     * 已在抛出点 log.error 记录内部细节，此处仅回友好短文案，绝不透传内部信息。
     * 注意：该错误响应以明文返回（此时通常尚未/无法加密），保证前端能读到可读错误。
     */
    @ExceptionHandler(ApiCryptoException.class)
    public AjaxResult handleApiCryptoException(ApiCryptoException e, HttpServletRequest request)
    {
        log.error("请求地址'{}', 接口加解密异常: {}", request.getRequestURI(), e.getMessage());
        return AjaxResult.error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * 演示模式异常
     */
    @ExceptionHandler(DemoModeException.class)
    public AjaxResult handleDemoModeException(DemoModeException e)
    {
        return AjaxResult.error(MessageUtils.message("error.demo.mode"));
    }

    /**
     * 文件上传超过大小限制：单独处理并返回可读短文案，避免落入通用异常的「系统繁忙」。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public AjaxResult handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e, HttpServletRequest request)
    {
        log.warn("请求地址'{}', 上传文件超过大小限制, maxBytes={}", request.getRequestURI(), e.getMaxUploadSize(), e);
        return AjaxResult.error(HttpStatus.ERROR, "文件过大");
    }

    /**
     * 数据库唯一约束冲突：避免把 SQL 异常原文透给前端，返回可读短文案。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public AjaxResult handleDuplicateKeyException(DuplicateKeyException e, HttpServletRequest request)
    {
        log.warn("请求地址'{}', 数据重复", request.getRequestURI(), e);
        return AjaxResult.error("数据已存在");
    }

    /**
     * 数据库完整性约束违反（外键/非空/长度等）：不把 SQL 字段名泄漏给前端。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public AjaxResult handleDataIntegrityViolation(DataIntegrityViolationException e, HttpServletRequest request)
    {
        log.warn("请求地址'{}', 数据完整性异常", request.getRequestURI(), e);
        return AjaxResult.error("数据非法");
    }
}
