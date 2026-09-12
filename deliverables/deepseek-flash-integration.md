# DeepSeek Flash 接入说明

## 已接入的模型能力

实际模型标识为 `deepseek-flash`，展示名为 DeepSeek V4.1 Flash。使用原 DeepSeek 供应商，不创建重复供应商，不覆盖已有模型、功能池、密钥、网关和倍率。

| 能力 | 系统实现 |
| --- | --- |
| 文本、图片理解 | 官方 Chat Completions；复用统一文本任务、权限、并发、报价及结算 |
| 图像输入 | 对象存储 URL，JPEG/PNG/GIF/WebP，auto/low/high/original |
| 图像硬约束 | 最多600张；单图32MiB，总计64MiB；边长8192，15张起4096；URL最长8192字符；仅user消息 |
| 输出 | 流式与非流式；最大393216 Token；平台默认预留4096 Token，显式输出值按能力校验 |
| 思考 | 默认high；minimal/low→low，medium/high/xhigh→high，max/ultra→max；可显式关闭 |
| 工具调用 | 函数定义、工具选择、函数结果关联；思考模式拒绝required及指定函数；保留每轮助手思考内容用于续轮 |
| JSON输出 | text/json_object；不声明支持JSON Schema输出 |
| 前缀续写 | 最后一条assistant设置prefix；使用beta对话端点，后台有结构化能力开关 |
| FIM补全 | fim能力，beta/completions，prompt及suffix，非思考纯文本，最多4096 Token |
| Token成本 | 输入2、缓存命中输入0.04、输出8元/百万Token；按用量区分缓存桶，不重复计思考输出 |

图片预扣按官方单图1024 Token上限估算，结算读取上游真实usage。报价上下文仍使用平台保守估算与安全余量，不提供精确Tokenizer。工具参数与任务摘要保留平台已有长度保护，思考续轮只在当前响应内存中传递，不将思考正文写入数据库。

## 接入边界

- 网站计费沿用固定高峰SKU；官方空闲价（输入1、缓存0.02、输出4）记录在价格来源元数据，**未实现自动按高峰/空闲时段计价**。
- Responses、Anthropic是官方替代API，本次采用Chat承载模型对话能力，未新增这两个DeepSeek协议适配器。
- Files API及Base64是替代上传方式，本次沿用系统对象存储URL，不接受上游file_id，因此不开放Files专属64MiB单图/200MiB总量额度。
- logprobs可传官方参数，但统一业务接口不承诺返回逐Token概率明细；既有任务只交付正文、可选实时思考、工具上下文与用量。
- Web不在本次修改范围。多模态消息、工具执行/续轮、前缀和fim选择需对应调用方使用现有接口字段；不能把服务端支持等同于所有Web入口已有按钮。

## 数据库与回滚

已有安装从 v2.1.0 执行 `sql/v2.1.1.sql`；新安装已包含在 `sql/aid-init.sql`。模型默认停用，先配置有效DeepSeek凭证并检查业务池绑定，再启用。脚本重复运行不覆盖站长编辑结果。

回滚优先通过后台停用新模型、解除本次新增的功能池绑定，再回退对应代码；不删除有历史任务或账单引用的模型。脚本不修改原模型，不需要恢复原供应商凭证。

## 官方证据

- [模型与价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)
- [Chat请求参数及393216输出上限](https://api-docs.deepseek.com/api/create-chat-completion/)
- [图片方式与限制](https://api-docs.deepseek.com/zh-cn/guides/vision/)
- [思考模式及工具续轮](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode/)
- [前缀续写](https://api-docs.deepseek.com/zh-cn/guides/chat_prefix_completion/)
- [FIM补全](https://api-docs.deepseek.com/zh-cn/guides/fim_completion/)
- [FIM参数](https://api-docs.deepseek.com/api/create-completion/)

官方Vision指南只允许user图片，Chat参数页还列出tool图像，两处描述不一致；当前按Vision指南的明确限制校验，不将存在冲突的扩展标记为已支持。
