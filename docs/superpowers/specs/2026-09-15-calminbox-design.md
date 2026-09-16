# 安心收件箱 CalmInbox — 设计文档

- 日期：2026-09-15
- 状态：已获用户批准
- 项目性质：Android 原生（Kotlin）端侧 AI 应用，求职作品集单仓项目

## 1. 背景与目标

作者为应届/在校生，目标应聘厦门量子堆栈科技有限公司「AI-Native 工程师（Vibe Coding）」岗位。该公司是面向下沉市场的工具类应用公司（悟空分身、视频大全等，服务 1.8 亿用户），使命为「发现世界上有价值且重复的事，让智能化实现它」，全员有 GitHub 开源项目，CEO 亲自筛选简历，明确看重产品思维与 AI 驱动开发（Cursor/Trae）能力，JD 中出现 lobechat、SillyTavern、MCP、TTS 等关键词。

本项目需满足：

1. 展示原生 Android Kotlin 能力（Compose、系统服务、后台任务）
2. 展示端侧大模型落地能力（MNN 推理集成与调优）
3. 有真实产品叙事（端侧 AI 是必需品而非演示品）
4. 2~3 周内由应届生借助 AI 驱动开发完成 MVP
5. 单仓 GitHub 项目，clone 即可复现，含 CI 与 Release

## 2. 产品定义

**一句话**：端侧 AI 通知管家——接管通知流，本地小模型自动分类降噪、生成每日简报，支持自然语言问答，全程零上传。

**用户故事**：

- 每天收到 100+ 条通知，重要信息（验证码、快递、账单）被营销推送淹没
- 想一眼看懂今天发生了什么，而不是逐条翻通知
- 不想让通知内容（验证码、私密信息）离开手机

**隐私即卖点**：通知内容高度敏感，推理必须在本地完成，端侧 LLM 是产品的必要条件。

## 3. MVP 功能范围

| # | 功能 | 说明 |
|---|------|------|
| 1 | 通知接入与存储 | NotificationListenerService 监听 → 规则过滤（包名黑白名单）→ Room 入库，去重（pkg+time+title hash） |
| 2 | AI 打标分类 | 规则引擎先行（包名映射类目），LLM 批量兜底：输出分类目 + 重要度 1~5 + 一句话摘要 |
| 3 | 每日简报 | WorkManager 22:00 触发，端侧生成「今日重要事项 Top5 + 分类统计」，存库并本地通知推送 |
| 4 | 自然语言问答 | 聊天界面，关键词检索（LIKE，FTS 默认分词器不支持中文，列入二期）+ 时间过滤检索通知，端侧流式回答（如「我的验证码是多少」），答案附可跳转的引用来源 |
| 5 | 降噪模式 | 营销/低重要度通知自动折叠聚合展示 |

**明确不做（本期 YAGNI）**：短信读取（二期）、云端模型与账号系统、embedding 式 RAG 与 FTS 全文索引（二期，本期用 LIKE 关键词检索）、iOS、多模块拆分工程。

## 4. 技术架构

### 4.1 技术栈

- Kotlin 2.x + Jetpack Compose + Material 3；单 Activity + Navigation；Hilt 依赖注入
- MNN Android LLM SDK，JNI 封装为 `LlmEngine`（加载 / 流式推理 / 空闲超时释放）
- 主力模型：Qwen2.5-1.5B-Instruct int4（约 1GB）；预留 Qwen3 系列对比基准
- Room（通知表 + 会话表；检索用 LIKE 关键词匹配）、WorkManager、DataStore
- 模型分发：首启从 ModelScope 下载，设置页管理（进度 / 删除）

### 4.2 包结构（分层不拆模块）

```
core/
  database/        Room 实体与 DAO
  model/           LlmEngine（MNN 封装）、ModelManager（下载与路径管理）
  notifications/   NotificationListenerService + 规则引擎
  classify/        分类 pipeline（规则 + LLM 批处理）
features/
  inbox/           通知流 UI（分类 tab、降噪聚合）
  brief/           每日简报页
  chat/            问答聊天页（流式渲染）
  settings/        模型管理、权限引导、过滤规则
```

### 4.3 关键技术决策

- **引擎单例 + 惰性加载 + 空闲超时释放**：模型常驻 1~2GB 内存会被系统回收；后台批处理与聊天共享同一实例
- **上下文控制**：每条通知截断 ≤100 字，每批 ≤20 条
- **批处理策略**：攒批（10 条或 5 分钟）触发，降低功耗与推理次数
- **降级链路**：模型未就绪 → 分类降级纯规则引擎；LLM 输出非法 JSON → 重试 1 次 → 降级规则

## 5. 核心数据流

1. **入库**：`onNotificationPosted` → 过滤（黑名单 / 本 App 自身通知）→ 去重 → Room insert
2. **打标**：待分类队列攒批 → 规则引擎先打（如淘宝→购物）→ 未命中才调 LLM（输入通知列表，输出约束为 JSON：`{id, category, importance, summary}`）→ 更新 Room
3. **简报**：WorkManager 定时 → 查询当日已分类通知 → LLM 生成简报 → 存库 + 本地通知推送
4. **问答**：用户提问 → FTS / 关键词 + 时间范围检索 → 拼 system prompt（「仅依据以下通知内容回答」）→ 端侧流式输出 → 引用跳转原通知

## 6. 错误处理与边界情况

| 场景 | 处理 |
|------|------|
| 通知权限被撤销 | 设置页检测并引导重新授权 |
| 模型未下载 / 加载失败 | 聊天页引导下载；分类自动降级规则引擎 |
| 推理 OOM / 进程被杀 | 捕获异常 → 自动释放引擎 → 提示并可重载 |
| LLM 输出非法 JSON | 重试 1 次 → 降级规则分类 |
| WorkManager 任务失败 | 退避重试 |
| 日通知量 500+ | 入库轻量化，批处理限速 |
| 首 token 延迟劣化 | 记录基准并在设置页展示 |

## 7. 测试策略与工程化（求职价值核心）

- 单元测试：分类规则引擎、prompt 构造、JSON 容错解析（JUnit）
- GitHub Actions：push 自动构建 APK + lint；打 tag 出 Release 附 APK
- README：GIF 演示、架构图、真机性能基准表（不同模型首 token 延迟 / tokens-per-sec / 内存峰值）、隐私声明、英文版说明
- README 含「How I built this with AI」章节：沉淀 Vibe Coding 工作流，正对岗位 JD
- 提交规范：conventional commits，开发过程可追溯

## 8. 成功标准

- 真机（作者的中高端安卓机）上随机抽样 50 条通知人工核对，分类准确率 ≥80%；简报可读；问答能正确回答验证码 / 快递类问题
- 首 token 延迟 <3s，内存峰值可控（App 不被频繁杀死）
- GitHub 仓库 clone 后 Android Studio 打开即可构建运行
- 三周节奏：W1 原生闭环（通知 + Room + 收件箱 UI，不含 LLM）→ W2 MNN 集成 + 打标 + 简报 → W3 问答 + 打磨 + README / CI / 发布

## 9. 二期展望（不在本期范围）

- 短信渠道接入（权限更敏感，单独设计）
- embedding 式本地 RAG（通知语义检索）
- 云端大模型可选接入（用户显式开关）
