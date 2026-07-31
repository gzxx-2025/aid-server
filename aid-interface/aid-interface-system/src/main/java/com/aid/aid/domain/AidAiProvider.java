package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.aid.common.annotation.Excel;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * AI大模型服务商(官方渠道)配置对象 aid_ai_provider
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
// toString 排除 apiKey / apiSecret / extraHeaders，避免日志或异常堆栈打印对象时泄露密钥与敏感 header。
@ToString(callSuper = true, exclude = {"apiKey", "apiSecret", "extraHeaders"})
@TableName(value = "aid_ai_provider")
public class AidAiProvider extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务商展示名称 (如: 字节火山引擎, OpenAI) */
    @Excel(name = "服务商展示名称 (如: 字节火山引擎, OpenAI)")
    private String providerName;

    /** 服务商唯一编码 (系统内路由标识, 如: bytedance) */
    @Excel(name = "服务商唯一编码 (系统内路由标识, 如: bytedance)")
    private String providerCode;

    /** 服务商LOGO图标URL（厂家品牌图标，所属模型共用；存相对路径，出参由 @MediaUrl 拼 OSS/COS/本地域名） */
    @Excel(name = "服务商LOGO图标URL")
    @MediaUrl
    private String logoUrl;

    /** 官方默认API网关地址 */
    @Excel(name = "官方默认API网关地址")
    private String baseUrl;

    /** 官方默认API秘钥 (加密存储) */
    /**
     * 使用 WRITE_ONLY：允许前端提交（add/edit）时写入，但序列化返回时永不输出明文，
     * 避免 API Key 经 list / getInfo 接口明文回传到管理端。
     */
    @Excel(name = "官方默认API秘钥 (加密存储)")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;

    /** 官方扩展秘钥 (如需) */
    @Excel(name = "官方扩展秘钥 (如需)")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiSecret;

    /** 任务查询路径模板（%s 为 taskId 占位符），用于异步任务轮询 */
    @Excel(name = "任务查询路径模板")
    private String taskQuerySuffix;

    /** 渠道状态 (0正常 1停用) */
    @Excel(name = "渠道状态 (0正常 1停用)")
    private String status;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

    /** 是否支持回调通知 (0不支持 1支持) */
    private Boolean supportsCallback;

    /** 默认调度策略JSON（供应商级） */
    private String scheduleStrategyJson;
    /** 鉴权 header 名（默认 Authorization；Azure OpenAI 用 api-key） */
    @Excel(name = "鉴权 header 名")
    private String authHeader;

    /** 鉴权前缀（默认 'Bearer '；部分厂商无前缀） */
    @Excel(name = "鉴权前缀")
    private String authPrefix;

    /** 自定义 header（JSON 对象，如 {"api-version":"2024-02-01"}）。
     * 可能含 token 类敏感信息，标记 WRITE_ONLY 不在 list/get 接口回显；
     * 编辑时若前端不提交此字段则保留原值。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String extraHeaders;

    /** 厂商级请求体附加参数（JSON 对象，如思考模式：{"thinking":{"type":"disabled"}}） */
    private String extraBody;

    /** 自定义 query string（JSON 对象，百度千帆等需要） */
    private String extraQuery;

    /** API Key 申请页直链 */
    private String apiKeyApplyUrl;

    /** 官方接口文档首页 */
    private String officialDocUrl;

    /** 官方定价页直链（后台配价时一键跳转核对官方价格，防止官方调价后无感知） */
    private String officialPriceUrl;

}
