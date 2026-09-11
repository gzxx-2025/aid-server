# 供应商账户查询与余额监控

## 官方契约

- [DeepSeek 余额](https://api-docs.deepseek.com/api/get-user-balance/)：`balance_infos` 按 CNY、USD 分别返回总额、赠送和充值余额；不同币种不合计。
- [DeepSeek 模型列表](https://api-docs.deepseek.com/api/list-models)：账户连通性检查使用只读模型列表，具体模型测试仍检查所选模型是否存在。
- [Vidu 积分](https://platform.vidu.com/docs/get-account-info)：`remains[].credit_remain` 是积分余量，不把并发数或排队数计入余额。
- [可灵资源包](https://klingai.com/document-api/api/assets/account-usage)：`data.resource_pack_subscribe_infos` 为资源包明细，余量存在约 12 小时统计延迟，查询 QPS 不超过 1。
- [MiniMax 任务列表](https://platform.minimaxi.com/docs/api-reference/video-generation-v2-list)：`items`、`total`，最近 7 天；模型与任务类型筛选均可选，创建／更新时间由秒转换为毫秒。

## 后台接口响应

`GET /aid/aidprovider/{id}/operations/capabilities` 在既有能力字段上补充 `balanceKind`（money / credits / resourcePackages）和 `balanceUnit`。任务查询保留 `recentDays`、`taskStatuses` 等声明。

`GET /aid/aidprovider/{id}/operations/balance`：

- 币种和积分响应保留 `balance`、`unit`、`queriedAt`；DeepSeek 保留 `balanceInfos`、`isAvailable`，Vidu 保留 `remains`。
- 可灵始终返回 `resource_pack_subscribe_infos` 数组、`balanceAvailable`、`unit=RESOURCE_UNITS`、`delayNotice` 和 `queriedAt`。空数组不等于账户现金余额为零，不生成虚构总余额。
- `queriedAt` 是查询时间（毫秒），不是金额。零值、负值正常保留，缺失或非法金额不自动补零。

`POST /aid/aidprovider/{id}/operations/tasks`：`result` 始终为数组，`nextCursor` 和 `hasMore` 控制分页；MiniMax 同时返回 `total`。可灵与 MiniMax 保留原有任务字段，并统一 `create_time`、`update_time`、`message`。MiniMax 仅列表所支持的视频任务，不声称覆盖音频及更早历史。

## 监控口径与配置

- DeepSeek 配置 CNY 或 USD，按对应币种监控；Vidu 配置 CREDITS。已有单位和阈值不自动修改或兑换。
- 可灵配置 RESOURCE_UNITS，查询历史购买记录，仅汇总相同名称、相同计量类型的有效资源包。不同种类资源包不能安全相加时，标记查询不可用，可结合模拟余额或余额不足错误规则；此数量不等同于人民币。空资源包列表也标记不可用，不误报零余额。
- API 查询失败只有在启用模拟余额时才可使用模拟值；无有效余额会打断连续低余额确认，不恢复或删除已有告警。
- 短信／微信／邮件继续复用现有监控确认、静默、重试和通知渠道；恢复消息显示“已恢复”。必须启用监控、勾选供应商、配置阈值、提醒人及渠道模板后才会发送。
- 供应商页面手动余额查询不依赖监控总开关。查询请求按登录账户、供应商和参数合并，刷新不会重复发送正在进行的同键查询。

本次无表结构或数据迁移，无需 SQL，不改写供应商凭证、价格、模型或用户积分。
