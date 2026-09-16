### Task 7: W1 收尾验收（回归 + 真机清单 + README 基线）

**前置依赖：** Task 1~6 全部完成并合入当前分支。

## Files

**Create:**
- docs/acceptance/w1-manual-checklist.md

**Modify:**
- README.md
- docs/superpowers/plans/batches/w1/task-07.md（只勾选执行完成的步骤，不改动任务要求）

## Interfaces

**Produces:** W1 可验收原生闭环、README 基线、docs/acceptance/w1-manual-checklist.md 实测记录。

**Consumes:** W1 全部实现。

## Step 1: 写验收文档

创建 docs/acceptance/w1-manual-checklist.md：

~~~markdown
# W1 原生闭环手动验收记录

## 环境
| 项目 | 记录 |
|---|---|
| 设备型号 |  |
| Android 版本 |  |
| 构建类型 | Debug |
| 测试日期 |  |
| 测试人 |  |

## 自动化基线
| 检查项 | 命令 | 结果 |
|---|---|---|
| 全量单测 | .\gradlew.bat :app:testDebugUnitTest |  |
| Lint | .\gradlew.bat :app:lintDebug |  |
| Debug 构建 | .\gradlew.bat :app:assembleDebug |  |

## 功能验收
| # | 场景 | 预期 | 结果 |
|---|---|---|---|
| 1 | 首次启动 | 四 tab 显示，默认进入收件箱 |  |
| 2 | 未授权进入设置页 | 权限卡片显示引导并可跳系统设置 |  |
| 3 | 授权后返回设置页 | 权限状态刷新为已授权 |  |
| 4 | adb 发送通知 | Room 入库并出现在收件箱 |  |
| 5 | 同一通知重复发送 | digest 去重，列表仍只有一条 |  |
| 6 | 超 100 字通知 | text 存储与展示均截断至 100 字 |  |
| 7 | 本 App 自身通知 | 不入库 |  |
| 8 | 黑名单添加包名 | 该包后续通知不入库 |  |
| 9 | 黑名单删除包名 | 该包后续通知恢复入库 |  |
| 10 | 已知包名规则映射 | category/importance 立即更新 |  |
| 11 | 未知包名 | 保持 UNCATEGORIZED，应用不崩溃 |  |
| 12 | 阈值 1 | 验证码在重要列表，营销折叠 |  |
| 13 | 阈值 3 | 低重要度也折叠 |  |
| 14 | 杀进程后重启 | Room 数据仍存在 |  |
| 15 | 飞行模式 | W1 全部功能仍可用 |  |
~~~

空结果列是验收记录表的一部分，执行时必须逐项填写，不能用估计值替代。

## Step 2: 执行自动化验证

~~~powershell
git status --short
.\gradlew.bat clean
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
~~~

**Expected:** clean、单测、lint、assemble 全部 BUILD SUCCESSFUL。若 lint 报错，只修复 W1 代码或 manifest，不通过 suppress 掩盖实际问题。

## Step 3: 真机手动验收

1. 连接真机并执行：

~~~powershell
.\gradlew.bat :app:installDebug
adb shell am start -n com.calm.inbox/.MainActivity
~~~

2. 按清单依次执行 15 项场景。
3. 使用以下命令制造可控通知：

~~~powershell
adb shell cmd notification post -t "登录验证码" CalmVerification "您的验证码是 246810，5 分钟内有效"
adb shell cmd notification post -t "限时大促" CalmMarketing "全场五折，仅限今天"
adb shell cmd notification post -t "长文本" CalmLong "$(python -c "print('x' * 180)")"
~~~

4. 重复发送同一 tag 与相同标题时，须保持 postedAt 语义可观察；若系统命令产生不同 postTime 导致无法直接验证去重，查看 Room 中 digest 字段并用 adb shell dumpsys 或 Android Studio Database Inspector 人工确认。
5. 在系统设置撤销通知访问权限，回到 App 设置页确认能检测并重新引导。
6. 开启飞行模式重复核心操作，确认 W1 零网络依赖。
7. 将实测值填入 docs/acceptance/w1-manual-checklist.md。

## Step 4: README 基线更新

README.md 必须包含：

- 项目一句话与隐私定位；
- 当前 W1 能力列表；
- 系统通知访问权限开启路径；
- 本地构建与测试命令；
- Room 表结构简述；
- W2/W3 后续计划；
- 声明模型与推理尚未在 W1 引入。

不得写 AI 分类已可用；W1 只有规则分类。

## Step 5: Commit 与里程碑

~~~powershell
git add README.md docs/acceptance/w1-manual-checklist.md docs/superpowers/plans/batches/w1/task-07.md
git commit -m "test: complete W1 native-loop acceptance checklist"
git tag v0.1.0-w1
~~~

**Expected:** 提交成功；git tag v0.1.0-w1 指向 W1 收尾提交。tag 只创建，不默认推送。
