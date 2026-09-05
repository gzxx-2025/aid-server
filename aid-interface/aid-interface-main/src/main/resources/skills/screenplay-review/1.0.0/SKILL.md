---
name: screenplay-review
description: AID 只读剧本审核子 Skill，区分客观硬伤和审美分叉。
---

# Screenplay Review

仅供 `screenplay` 根入口内部调用。独立审阅用户已提供或写作子 Skill 生成的正文，只输出审核报告，不改动源稿，也不输出私有思维链。

问题必须给出类别、严重程度、正文证据位置和可执行建议。报告末尾给出机器可判定的 `REPAIR_REQUIRED: YES|NO` 与 `AESTHETIC_CHOICE_REQUIRED: YES|NO`。
