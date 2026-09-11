# TokenDance 接入与模型管理

## 部署与安全

服务端和后台共同提供供应商账户、模型目录、模型能力、SKU、音色工作台。Web 不包含在本次变更中。

不需要配置额外的 TokenDance 凭证加密密钥或固定回调环境变量。后台在站长点击授权时按当前浏览器的协议、主机、端口和后台部署基路径生成回调页面地址；适用于已绑定域名或直接使用 IP 的站点。完整 Key 由服务端使用一次性 `code` 与 PKCE verifier 交换，并原文写入仅服务端访问的凭证表，不返回后台浏览器。部署方必须限制数据库及备份访问权限，并优先使用 HTTPS。

OAuth 创建的是站长本人账户下的新 Key，固定 App URL 为 `https://aidstudio.com.cn`、默认 Key 名称为“视觉AID”。授权 URL 始终使用 S256 PKCE。TokenDance 授权完成后只向后台页面追加 `code`；后台从同一浏览器会话读取本地保存的 `authorizationRef`，调用登录态管理接口完成交换和自动保存，不添加自定义回调参数，也不开放匿名服务端回调接口。授权码只交换一次；响应丢失时只查授权状态，不重放 `code`。完整 Key 丢失时只能重新授权，旧 Key 需由站长前往 TokenDance 官网删除。

新授权替换活跃凭证时，历史任务仍按已固定的凭证版本与模型路由执行。显式撤销的版本不再解密使用。自定义网关保持供应商配置，不把 Key 发给其他来源。

## 本地目录与费用

### 后台入口

模型管理页顶部常驻 TokenDance 推荐接入区，左侧供应商列表置顶并标记推荐，首次加载默认选中。点击“浏览模型目录”查看并导入模型，点击“授权与账户”处理 OAuth、余额与充值；顶部与侧栏共用弹窗，不重复查询。

新安装自动预置供应商；已完成此前升级的环境补执行 `sql/unreleased/060-tokendance-default-provider.sql` 并部署新的后台静态资源。仅新增缺失记录，默认停用且不含密钥；已有网关、密钥、名称、状态和模型配置不覆盖，软删除记录不自动恢复。完成授权、导入及价格/能力核验后显式启用供应商和所需模型。

原顶部统一网关模块不再展示，但本次不修改其已有配置及运行逻辑。推荐不等于全局路由切换，也不代表所有目录模型已完成真实调用验证。

推荐区参考 [Ant Design 信息组织与间距规范](https://ant.design/docs/spec/proximity/)，采用响应式栅格、主次操作和状态标签；不额外请求上游余额或启动授权。

`aid-interface/aid-interface-main/src/main/resources/tokendance/` 保存本地成本快照、官方能力证据和视频 Token 估算模板。目录按用途排除 Embedding、搜索/网页读取和文档解析，不定时抓价，也不采集分润价。

目录中证据状态与 SKU 状态、真实接通状态相互独立：`VERIFIED` 表示当前写入的能力有官方依据；`PARTIAL` 表示原厂尚未公开某些精确上限或渠道型号缺少逐型号原厂页，未公开字段保持为空。当前本地快照 82 个模型、141 个模型－协议组合均已生成完整成本 SKU；能力备注不会再把完整 SKU 误判为停用。它们仍不代表已经使用真实账户完成收费生成测试。

首次导入默认停用，由站长核对后显式启用。已有模型不覆盖网关、倍率、状态、能力、SKU 或功能池；已从旧目录导入但 SKU 为空或全部停用的模型，可在预览中按配置版本补齐本地成本 SKU。定价不明确的组合不编造费率、不按零价启用。合法零价仍生成统一零额计费快照；缺少价格不是免费。

详细证据见 [TokenDance 能力台账](tokendance-model-capability-evidence.md) 与 [其他模型能力台账](official-model-capability-evidence.md)。

## 后台接口

全部返回值包在 `data` 内，使用既有后台登录与权限。

| 方法及路径 | 输入与用途 |
|---|---|
| POST `/aid/tokendance/providers/{id}/oauth/authorizations` | `{mode:"CALLBACK"或"HEADLESS",keyName?,callbackUrl?}`；回调模式由后台提交当前站点回调页完整地址 |
| POST `/aid/tokendance/providers/{id}/oauth/authorizations/complete` | `{authorizationRef,code}`，完成回调或 Headless 的一次性交换并自动保存 Key |
| GET `/aid/tokendance/providers/{id}/oauth/authorizations/{ref}` | 查询发起管理员的授权状态 |
| GET `/aid/tokendance/providers/{id}/credential` | 仅查询凭证状态、版本、脱敏标识 |
| POST `/aid/tokendance/providers/{id}/credential/revoke` | 撤销本地活跃凭证 |
| GET `/aid/tokendance/providers/{id}/balance?force=false` | 人民币余额、额度、消耗与查询时间 |
| POST `/aid/tokendance/providers/{id}/payments` | `{amount,userConfirmed:true,requestId}`，创建充值会话 |
| POST `/aid/tokendance/providers/{id}/payments/{paymentId}/status` | 查询官方到账状态 |
| GET `/aid/tokendance/catalog?keyword=...` | 浏览本地目录 |
| GET `/aid/tokendance/catalog/{modelId}` | 成本、协议、能力和证据 |
| GET `/aid/tokendance/catalog/{modelId}/preview?providerId=...&protocol=...` | 导入差异、费用覆盖与阻断项 |
| POST `/aid/tokendance/catalog/import` | `{providerId,selections:[{modelId,protocol}]}`，幂等导入 |
| POST `/aid/aidmodel/sku-coverage` | 当前模型编辑草稿，检查有限参数组合的 SKU 覆盖 |

价格覆盖返回 `status`、`checkedCombinations`、`missingCombinations`、`conflicts` 和 `warnings`。状态包括 `NOT_APPLICABLE`、`COMPLETE`、`GAPS`、`CONFLICTS`、`REVIEW_REQUIRED`。该检查不生成价格；无法穷举的自定义宽高或未知条件明确提示需核验。正式报价仍按真实参数匹配有效 SKU。

模型更新必须携带最新 `configVersion`。能力和计费配置分别合并；未提交字段保留，显式删除通过编辑器表达。嵌套未知字段无损保留，SKU 以稳定 `skuCode` 关联，不按数组下标猜测。

## 音色工作台

发布样本文本仅保留不超过 500 字且未开启文本优化的站长试听原文。更长文本不截断成看似完整的转写；开启文本优化时，原文不能作为实际样本转写。普通用户配音的终态正文清理规则不变。

| 方法及路径 | 用途 |
|---|---|
| GET `/aid/voice-workbench/capabilities?modelId=...` | 查询真实操作、输入字段、发布目标与限制 |
| POST `/aid/voice-workbench/samples` | multipart：`modelId`、`rightsConfirmed`、`file`；取得受控样本引用 |
| POST `/aid/voice-workbench/quote` | 报价并取得管理员、模型及请求绑定的 `quoteRef` |
| POST `/aid/voice-workbench/create` | 确认授权和费用后创建统一任务 |
| GET `/aid/voice-workbench/tasks/{taskId}` | 只读查询本人制作任务，不重新生成 |
| POST `/aid/voice-workbench/publish` | 将已存储结果保存为草稿或发布到音色库 |

制作请求字段：`modelId`、`operation`、`voiceCode`、`text`、`sourceFileId`、`description`、`audioFormat`、`sampleRate`、`speechRate`、`loudnessRate`、`pitch`、`emotion`、`optimizeTextPreview`，以及创建时的 `rightsConfirmed`、`chargeConfirmed`、`idempotencyKey`、`quoteRef`。仅提交 capabilities 声明支持的字段。`operation` 区分 `SYNTHESIZE`、`REGISTER_CLONE`、`REFERENCE_CLONE`、`DESIGN`；参考样本不是注册后的 voice ID。

后台制作复用媒体任务和终态结算，以站长作为成本承担方，不扣 C 端积分。实际字符和 Token 用量写入任务计费快照；完整用量可获得时按冻结成本单价计算，不乘网站积分倍率，缺失用量不假记为零。任务提交结果不确定时不自动重新注册；结果转存失败也不自动再次生成。普通配音只用于试听，不发布为新音色。复刻与设计的发布目标由能力接口返回，并在写入前锁定、复核目标模型，不能将设计结果错误发布到不兼容的合成模型。

同步音频的转存恢复文件保存在 `aid.profile` 同级 `<profile>-private/media-audio-recovery/tokendance`，不得通过 Nginx、静态资源或下载接口公开。Redis 分布式锁和提交标记必须保留；音频字节不写入数据库或 Redis。转存失败后，相同身份、凭证版本及有效请求只重试转存，保留真实用量。多节点需要共享该私有目录，或由原节点处理恢复；其他节点缺少文件时拒绝重新生成。未完成文件保留 7 天，目录达到容量检查阈值后拒绝新生成；文件到期或丢失不解除不确定提交标记。运维必须先核对原任务及官方调用记录，再处理恢复文件和对应标记，不得批量清空 Redis 来重试收费请求。

## 余额与充值

手动查询与余额提醒复用凭证版本缓存和相同进行中请求。手动查询不依赖监控开关；查询失败显示错误，不能伪装成零余额。微元只在服务端换算一次。

充值只保留必要会话、订单标识和状态，不建立第三方账单总账、不发放网站用户积分。只有官方状态为 `PAID` 才确认到账，终态只触发一次权威余额刷新。充值记录详情、退款及官方账户账单到官网查看。创建结果不确定时保留状态，避免重复建单。

## Web 后续配合清单

### 服务端文本工具续轮契约

此能力是 `IMediaGenerationService` 和 Provider SPI 的服务端契约，不是新增 C 端 HTTP 接口，也不自动执行任何函数。现有 Skill 普通文本接收器不具备工具执行器，仍在预扣前拒绝工具请求。业务要启用工具循环，须配置自身工具白名单、参数 Schema、用户权限、操作确认及循环上限。

模型需明确声明 `supportsToolCalling=true`。`options.tools` 使用所选协议的客户端函数定义：Chat 的 `type=function` 与嵌套 `function`，Responses 的平铺函数定义，Anthropic 的 `name/input_schema`；不接受无价格的上游托管搜索或代码工具。

`MediaTextGenerateRequest.messages` 的普通 `role/content/parts` 不变，新增可选字段：

| 字段 | 语义 |
|---|---|
| `toolCalls` | assistant 消息的函数调用数组，每项含 `id`、`name`、JSON 对象字符串 `arguments` |
| `toolCallId` | role=tool 时必填，引用上一条 assistant 的调用 ID；`content` 是结果字符串，允许空字符串 |
| `toolError` | Anthropic 工具结果是否失败，映射 `is_error` |
| `reasoningContent` | Chat/Kimi 原样回传的思考上下文，只保留内存 |
| `thinkingBlocks` | Anthropic 原样签名块：`type=thinking` 的 `thinking/signature`，或 `type=redacted_thinking` 的 `data`；只保留内存 |
| `responseId` | Responses 本轮响应 ID，可作为下一轮 `options.previous_response_id` |
| `responseItems` | Responses 完整助手输出项，含函数调用与加密思考；无状态续轮须原序回传，只保留内存 |

函数结果必须紧跟对应调用；并行调用的结果全部补齐后才能加入新消息。重复、悬空、缺失 ID、非法 JSON 参数或协议不符均在上游调用前拒绝。Anthropic 并行结果合并为同一 user 消息的 tool_result 块；Responses 保留 function_call/call_id/function_call_output 的原生语义。

同步结果 `MediaTaskResponse.toolMessage` 返回完整 assistant 续轮对象；首次结果 `toolContextAvailable=true`，历史查询或幂等重放为 `false`（无工具结果时二者为空）。历史仅保留函数结果摘要，不能据此恢复思考签名或加密上下文。流式接收器必须显式返回 `supportsToolMessages()=true`，成功终态后接收一次 `onToolMessage`，再接收 `onDone`；历史工具轮不会重新提交上游，而是提示上下文仅首次返回。不要对已成功但丢失上下文的调用自动换幂等键重试。

续轮仍按每次实际模型调用独立进入统一权限、能力、并发、报价、预扣和结算。工具定义及回传参数纳入输入估算，实际费用按官方 usage 和原计费快照结算，不增加函数执行费或新费率。并发已满时工具请求不进入会丢失内存上下文的持久化队列，而是在建任务和预扣前返回稍后重试；普通请求排队不变。

核对顺序及依据：先阅读 [TokenDance 接入索引](https://tokendance.space/docs/ai-integration)，再沿 [Kimi 思考说明](https://tokendance.space/docs/kimi-thinking-models)、[Responses](https://tokendance.space/docs/protocol-openai-responses)、[Anthropic Messages](https://tokendance.space/docs/protocol-anthropic-messages) 核对渠道；缺少的续轮字段参照 [Kimi 原厂](https://platform.kimi.com/docs/guide/use-thinking-models)、[OpenAI 函数调用](https://developers.openai.com/api/docs/guides/function-calling)、[Anthropic 工具结果](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)。本地协议契约测试不代表真实账户全模型生成验收。

### 现有页面配合

1. 单分镜视频生成及同请求报价支持可选 `referenceVideoRecordIds`，按顺序提交站内视频记录 ID；不要自行拼接上游视频 URL。多分镜携带该字段会被拒绝。
2. 根据模型返回能力限制模态、数量、时长、尺寸及输入组合。前端限制仅改善体验，服务端为最终权威；不要裁掉超量媒体后继续提交。
3. 只读报价中的 `estimated`/`determined` 按原契约展示；尚未核验媒体不能展示为确定扣费。正式提交可能因可信元数据超限而拒绝，应保留用户输入供修改。
4. Skill 显式模型选择应传真实系统 `modelCode`；更换模型或内容后使用新幂等键，不重复重放原失败 Run。
5. 所有请求按业务请求键合并进行中调用，重复按钮、弹窗和事件不重复提交；终态刷新只走一个调度入口。
6. 后台的站长充值及凭证接口不提供给 C 端普通用户。C 端原积分充值、积分流水及模型扣费记录保持原业务。

## 升级与回退

依次执行 `sql/unreleased/020-*`、`030-*`、`040-*`、`050-*`、`060-*`。不要用完整初始化 SQL 覆盖现有数据库。`020-*` 直接建立当前 OAuth、凭证版本和充值会话表，不包含未投用旧草案的兼容字段；`060-*` 幂等预置推荐供应商。独立本次 SQL 交付与待发布脚本内容一致，不包含其他功能迁移。

升级前备份数据库、部署配置及媒体存储，并把数据库备份按敏感凭证管理。暂停新任务后升级服务端与后台，再检查授权、目录和模型配置。需要回退时先停用新增供应商并等待在途任务收口，恢复旧程序；新增表与列可保留，不应删除历史凭证和任务数据。能力补充脚本只添加缺失字段，但回退能力配置应从升级前备份逐模型恢复，不能批量清空 JSON。

未执行真实收费生成、充值付款或生产 SQL。部署方仍需在授权环境核验 OAuth 回调可达、对象存储、FFprobe、余额通知渠道及完整后台交互。
