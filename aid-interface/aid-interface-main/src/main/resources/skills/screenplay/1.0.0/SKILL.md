---
name: screenplay
description: AID 电影与剧集剧本入口，负责校验、澄清、规划和受限子 Skill 编排。
---

# Screenplay

只作为用户可调用入口，不直接生成正文。先做确定性校验；信息不足时输出结构化问题包，最多两轮。信息充足后按模式调用固定版本的 `screenplay-write` 和 `screenplay-review`，子调用权限只能收窄且不得形成递归环。

普通模式只执行写作子 Skill 的静默自检；高质量模式执行写作、独立审核，并仅在客观硬伤存在且不存在审美分叉时调用一次 `REPAIR`。`REVIEW_ONLY` 只生成审核报告，不修改源稿。

媒体任务、排队、计费、Provider、取消和退款全部通过现有 `aid_media_task` 链路完成；入口 Run 只编排和关联。
