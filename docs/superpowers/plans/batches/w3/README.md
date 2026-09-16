# CalmInbox W3 问答打磨与发布 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 在 W1 原生闭环与 W2 本地模型能力之上完成自然语言问答、边界降级、工程化发布、作品集叙事与 50 条抽样评估。

**Architecture:** 检索层先从问题提取关键词与时间范围，再通过 Room LIKE 查询获得可引用通知；ChatRepository 只把检索结果放入受约束 prompt 并消费同一 LlmEngine 流式输出；UI 与后台共享模型单例，异常时释放并引导恢复。发布层以 GitHub Actions 保证 clone 即构建，以真机基准与人工抽样证明产品可用。

**Tech Stack:** Kotlin 2.0.20、Jetpack Compose Material 3、Hilt、Room LIKE、WorkManager、MNN LlmEngine、JUnit4/Robolectric/Turbine/Truth、GitHub Actions、GitHub Releases。

**Spec:** docs/superpowers/specs/2026-09-15-calminbox-design.md

## Global Constraints

- 必须复用 W2 的 LlmEngine、EngineHolder、ModelManager、FakeLlmEngine、LatencyRecorder 与 BriefWorker，不另建模型通道。
- 问答仅依据本地通知检索结果，禁止云端调用，禁止把通知上传。
- 检索使用 NotificationDao.searchByKeyword + LIKE；FTS 与 embedding RAG 属二期。
- TimeQueryParser 支持：今天、昨天、前天、前天以前全部、最近N天、这周。
- 每次最多返回 50 条引用候选；答案必须带可跳转引用。
- 模型未就绪时聊天页引导下载；通知权限撤销时设置页检测并引导重新授权。
- 推理异常或 OOM：捕获 Throwable，调用 engine.release()，状态置 ERROR，提供重试。
- WorkManager 失败退避重试，不得重复生成同日简报。
- CI push 跑 lint/test/assemble；打 tag 生成 Release 并附 APK。
- README 必须含 GIF、架构图、真机基准表、隐私声明、英文版说明与 How I built this with AI。
- 最终抽样 50 条通知人工核对，分类准确率 ≥80%；首 token 延迟 <3s。
- 本机命令 .\gradlew.bat task；CI 用 ./gradlew task；每任务 conventional commit。

## Task Sequence

| Task | File | Deliverable |
|---|---|---|
| 14 | task-14.md | 关键词提取、时间解析、LIKE 检索组装 |
| 15 | task-15.md | ChatRepository、流式问答 UI、引用跳转 |
| 16 | task-16.md | 模型/权限/OOM/退避边界打磨 |
| 17 | task-17.md | GitHub Actions 构建、测试、Release APK |
| 18 | task-18.md | README、架构图、基准、英文版、AI 工作流 |
| 19 | task-19.md | 50 条准确率评估、v1.0.0 发布、最终验收 |

Task 14~16 必须顺序执行；Task 17 可在 Task 16 完成后开始；Task 18 依赖真实演示素材；Task 19 必须最后执行。
