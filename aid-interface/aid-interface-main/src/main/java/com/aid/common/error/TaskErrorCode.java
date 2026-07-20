package com.aid.common.error;

/**
 * 统一任务错误码枚举。
 * 前端根据 errorCode 做机器判断，不再猜文案。
 * 新增错误码时请同步更新前端映射表和 Business-API.md 文档。
 *
 * @author 视觉AID
 */
public enum TaskErrorCode {
    /** 用户余额不足（预冻结失败） */
    USER_BALANCE_NOT_ENOUGH("USER", "BALANCE", "用户余额不足，请充值后重试", true, "USER", true),

    /** 用户输入参数非法 */
    USER_INPUT_INVALID("USER", "USER_INPUT", "输入参数有误，请检查后重试", false, null, false),

    /** 用户输入触发内容安全拦截（区别于上游内容拦截，这是本平台预检测触发） */
    USER_CONTENT_VIOLATION("USER", "USER_INPUT", "内容不符合规范，请修改后重试", false, null, false),

    /** 文件格式不支持 */
    USER_FILE_FORMAT_INVALID("USER", "USER_INPUT", "文件格式不支持，请更换后重试", false, null, false),

    /** 文件大小超限 */
    USER_FILE_TOO_LARGE("USER", "USER_INPUT", "文件过大，请压缩后重试", false, null, false),

    /** 文件 URL 无法下载 */
    USER_FILE_DOWNLOAD_FAILED("USER", "USER_INPUT", "文件链接无法访问，请更换后重试", false, null, false),

    /** 图像分辨率不符合模型要求 */
    USER_IMAGE_RESOLUTION_INVALID("USER", "USER_INPUT", "图像尺寸不符合要求，请更换图片", false, null, false),

    /** 图像质量问题（没有人脸、多人、背身、头部遮挡等） */
    USER_IMAGE_QUALITY_INVALID("USER", "USER_INPUT", "图像不符合要求，请更换后重试", false, null, false),

    /** 音频时长/采样率等不符合要求 */
    USER_AUDIO_INVALID("USER", "USER_INPUT", "音频不符合要求，请更换后重试", false, null, false),

    /** 视频时长/分辨率/帧率不符合要求 */
    USER_VIDEO_INVALID("USER", "USER_INPUT", "视频不符合要求，请更换后重试", false, null, false),

    /** 输入文本为空 / messages 缺失 */
    USER_INPUT_EMPTY("USER", "USER_INPUT", "输入内容为空，请补充后重试", false, null, false),

    /** 输入内容长度超出模型上下文限制 */
    USER_INPUT_TOO_LONG("USER", "USER_INPUT", "输入内容过长，请精简后重试", false, null, false),
    /** 商户/平台额度不足 */
    MERCHANT_QUOTA_EXHAUSTED("MERCHANT", "QUOTA", "服务额度不足，请联系管理员", true, "MERCHANT", false),

    /** 上游供应商免费层耗尽（如 "The free tier of the model has been exhausted"） */
    PROVIDER_FREE_TIER_EXHAUSTED("PROVIDER", "QUOTA", "模型免费额度已用完", true, "MERCHANT", false),

    /** 上游供应商付费额度不足 */
    PROVIDER_QUOTA_EXHAUSTED("PROVIDER", "QUOTA", "模型额度不足", true, "MERCHANT", false),
    /** 上游认证/授权失败（HTTP 401/403） */
    UPSTREAM_AUTH_INVALID("PROVIDER", "UPSTREAM_TECH", "模型服务认证失败", false, null, false),

    /** 上游模型/服务未开通（ServiceNotOpen / ModelNotOpen / 未激活） */
    UPSTREAM_SERVICE_NOT_OPEN("PROVIDER", "UPSTREAM_TECH", "模型服务未开通，请联系管理员", false, null, false),

    /** 上游限流（HTTP 429） */
    UPSTREAM_RATE_LIMITED("PROVIDER", "UPSTREAM_TECH", "请求过于频繁，请稍后重试", false, null, true),

    /** 上游超时 */
    UPSTREAM_TIMEOUT("PROVIDER", "UPSTREAM_TECH", "模型响应超时，请重试", false, null, true),

    /** 上游服务端错误（HTTP 5xx） */
    UPSTREAM_SERVER_ERROR("PROVIDER", "UPSTREAM_TECH", "模型服务异常，请稍后重试", false, null, true),

    /** 上游参数错误（HTTP 400） */
    UPSTREAM_BAD_REQUEST("PROVIDER", "UPSTREAM_TECH", "请求参数异常", false, null, false),

    /** 上游返回内容安全拦截 */
    UPSTREAM_CONTENT_FILTERED("PROVIDER", "UPSTREAM_CONTENT", "内容不符合安全规范，请修改后重试", false, null, false),
    /** 结果缺失（上游说成功但无 URL / 无文本） */
    RESULT_INVALID("PLATFORM", "RESULT", "生成结果异常", false, null, true),

    /** OSS 持久化失败 */
    OSS_PERSIST_FAILED("PLATFORM", "INTERNAL", "文件存储失败，请重试", false, null, true),

    /** AI 生成失败（兜底，无法归类的 LLM/图片/视频生成错误） */
    AI_GENERATION_FAILED("PLATFORM", "INTERNAL", "AI生成失败，请重试", false, null, true),

    /** 回调失败（上游回调丢失/超时,降级轮询） */
    CALLBACK_FAILED("PLATFORM", "INTERNAL", "任务处理异常，请重试", false, null, true),

    /** 数据库写入失败 */
    PERSIST_FAILED("PLATFORM", "INTERNAL", "数据保存失败，请重试", false, null, true),

    /** 任务被中断 */
    TASK_INTERRUPTED("PLATFORM", "INTERNAL", "任务被中断", false, null, true),

    /** 未知错误（兜底） */
    UNKNOWN("PLATFORM", "INTERNAL", "未知错误", false, null, false);

    /** 错误来源：USER / MERCHANT / PROVIDER / PLATFORM */
    private final String errorSource;

    /** 错误大类（粗粒度）：BALANCE / QUOTA / UPSTREAM_TECH / UPSTREAM_CONTENT / RESULT / INTERNAL / USER_INPUT */
    private final String errorType;

    /** 面向用户的友好提示（前端可直接展示） */
    private final String userMessage;

    /** 是否需要充值 */
    private final boolean needRecharge;

    /** 充值主体：USER / MERCHANT / null（不需要充值时为 null） */
    private final String rechargeOwner;

    /** 是否可重试 */
    private final boolean retryable;

    TaskErrorCode(String errorSource, String errorType, String userMessage, boolean needRecharge, String rechargeOwner, boolean retryable) {
        this.errorSource = errorSource;
        this.errorType = errorType;
        this.userMessage = userMessage;
        this.needRecharge = needRecharge;
        this.rechargeOwner = rechargeOwner;
        this.retryable = retryable;
    }

    public String getErrorSource() {
        return errorSource;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public boolean isNeedRecharge() {
        return needRecharge;
    }

    public String getRechargeOwner() {
        return rechargeOwner;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
