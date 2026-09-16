# CalmInbox W1 原生闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 在不引入任何 LLM 依赖的情况下完成 CalmInbox 的 Android 原生闭环：通知监听、去重入库、规则分类、设置管理、降噪收件箱 UI 与 W1 验收。

**Architecture:** 先建立单模块 :app 与 Hilt/Navigation 基线，再以 Room 作为唯一通知事实源；NotificationListenerService 只做系统事件提取，过滤、截断与入库落在可测试的 Kotlin 类中；规则分类在 W1 立即生效，W2 的 HybridClassifier 只替换打标链路而不改动数据契约。

**Tech Stack:** Kotlin 2.0.20、Gradle 8.9、AGP 8.5.2、Jetpack Compose BOM 2024.09.03、Material 3、Hilt 2.52、Room 2.6.1、DataStore Preferences 1.1.1、JUnit4、Robolectric 4.13、Truth、Turbine。

**Spec:** docs/superpowers/specs/2026-09-15-calminbox-design.md

## Global Constraints

- 单模块 :app，app id com.calm.inbox；compileSdk 34，minSdk 26，targetSdk 34，Java 17。
- 版本统一写入 gradle/libs.versions.toml；W1 只允许引入骨架列出的依赖和测试依赖。
- 包结构保持 core/database、core/model、core/notifications、core/classify、features/inbox、features/brief、features/chat、features/settings，不拆 Gradle 模块。
- W1 不创建 MNN、OkHttp 下载实现或模型 UI；这些属于 W2。
- 每条通知 text 截断 ≤100 字；去重摘要固定 sha256("$packageName|$postedAt|$title")。
- 黑名单默认必须包含 com.calm.inbox；W1 分类必须能在无模型时完整可用。
- 通知检索本期使用 LIKE，不做 FTS、embedding RAG、短信、云端模型或 iOS。
- 所有时间逻辑注入 java.time.Clock；所有 Flow 测试用 Turbine；Room DAO 测试用 Robolectric。
- 本机命令使用 .\gradlew.bat task；每个任务至少一个 conventional commit。

## Task Sequence

| Task | File | Deliverable |
|---|---|---|
| 1 | task-01.md | 可构建的 Android 空壳、版本目录、Hilt 与四 tab NavHost |
| 2 | task-02.md | Room 三实体三 DAO、AppDatabase 与数据层测试 |
| 3 | task-03.md | 通知监听、过滤、截断、去重与入库 |
| 4 | task-04.md | 包名规则分类引擎与重要度映射 |
| 5 | task-05.md | DataStore 黑名单、降噪阈值、设置页与权限引导 |
| 6 | task-06.md | 通知流、分类 tab 与降噪聚合 UI |
| 7 | task-07.md | W1 回归、真机验收、README 基线与里程碑 |

执行者必须按编号执行；Task 8 开始前不得跳到 W2。
