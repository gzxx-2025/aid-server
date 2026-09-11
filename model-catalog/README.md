# 远程模型目录维护

不兼容模型的禁止导入决策单独维护，见 [兼容性策略](compatibility-policy.md)。更新成本、能力和目录快照不得移除该策略。

TokenDance 模型目录由不可变数据包和签名的 `tokendance/latest.json` 组成。服务端只接受 HTTPS、受信任仓库域名、正确 SHA-256 且通过 Ed25519 校验的目录；失败时继续使用最近可信缓存或 JAR 内置快照。

维护成本、能力、证据或视频 Token 估算后，先完成服务端目录编译和能力校验，再使用发布环境中受控保存的清单签名密钥生成新目录：

```powershell
./model-catalog/build-tokendance-catalog.ps1 `
  -CatalogVersion 2026.09.09.1 `
  -MinimumAppVersion 2.0.1 `
  -SigningKeyPath <受控密钥路径>
```

发布顺序固定为：先把新的不可变 `tokendance-catalog-<revision>.json` 同步到 Gitee 和 GitHub，确认两个地址及摘要一致，最后更新两个仓库的 `tokendance/latest.json`。不要覆盖已经发布的不可变文件。

仅调整现有契约内的模型、能力或 SKU 数据时可以只发布目录；出现新协议、新能力字段或新计费语义时，必须先发布支持该契约的程序，并在目录中填写对应 `minimumAppVersion`、`requiredFeatures` 或更高契约版本。旧程序会将不兼容项置灰，不能导入。
