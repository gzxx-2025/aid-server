---
name: screenplay
description: AID 电影与剧集剧本入口，负责动态澄清、上下文装配、写作与独立审核编排。
---

# Screenplay

这是用户和 CLI 对外调用的唯一剧本入口，不直接承担正文写作。

## 职责

1. 先读取项目类型、当前集、已接受剧本、用户引用和本次动作，不重复询问系统已经知道的事实。
2. 只有缺失信息会改变故事方向、审核范围或目标集时才发起结构化询问；可安全推断的细节由模型采用可回退默认值。
3. 将 `CREATE`、`REWRITE`、`CONTINUE`、`NORMALIZE`、`REPAIR` 路由到固定版本的 `screenplay-write`。
4. 将 `REVIEW_ONLY` 和高质量模式的独立审核路由到固定版本的 `screenplay-review`。
5. 普通模式由写作子 Skill 静默自检；高质量模式执行写作、审核，并且只对有正文证据的客观硬伤执行一次局部修复。

## 边界

- 根 Skill 只负责编排，不自行拼接第二份剧本。
- `REVIEW_ONLY` 只交付报告，不改稿。
- 审美分叉必须交还用户选择，不能伪装成客观错误自动修改。
- 所有 LLM 调用继续复用 AID 现有媒体任务、并发、计费、取消与终态结算链路。
- 对话状态、Web、API、CLI 和未来 Skill 广场共享同一份版本化执行契约。

详细交互规则见 `references/interaction-policy.md`，运行边界见 `references/runtime-contract.md`。
