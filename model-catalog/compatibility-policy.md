# AID 兼容性禁止导入策略

`aid-interface/aid-interface-main/src/main/resources/tokendance/catalog/incompatibility-policy.json`
独立维护 `modelId → importBlocked / reason`，按 TokenDance 上游模型身份生效，不使用站点数据库 ID，且覆盖该模型的所有协议。

本地禁入与在线目录禁入按“禁止优先”判定。目录未提供、删除或改为允许均不能解除内置禁入；解除必须单独审查策略文件，不能通过更新成本或能力自动恢复。模型保留在目录用于解释原因，列表与详情返回 `AID_INCOMPATIBLE`，`importable` 和 `selectable` 均为 `false`，直接调用导入或目录 SKU 修复同样拒绝。

目录生成器将策略写入签名包的 `aidCompatibilityPolicy`，并向 `compatibility.models` 投影 `deprecated=true`，让旧客户端也拒绝导入。禁止手改已签名包；通过 `model-catalog/build-tokendance-catalog.ps1` 重新生成新目录版本并签名，不变更程序版本。程序升级后即便仍读取旧远程目录，内置禁入也有效。

这些标记属于 AID 产品集成决策，不代表原厂模型故障。下线已有模型应使用后台受控下线接口，保留任务、用量、账单与历史结果；不能批量物理删除数据。
