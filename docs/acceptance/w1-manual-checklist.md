# W1 原生闭环手动验收记录

## 环境
| 项目 | 记录 |
|---|---|
| 设备型号 | 24129PN74C |
| Android 版本 | 16 |
| 构建类型 | Debug |
| 测试日期 | 2026-09-16 |
| 测试人 | Codex + 设备所有者 |

## 自动化基线
| 检查项 | 命令 | 结果 |
|---|---|---|
| Clean | .\gradlew.bat clean | PASS |
| 全量单测 | .\gradlew.bat :app:testDebugUnitTest | PASS（44 tests, 0 failures, 0 errors；最终 clean 门禁复跑通过） |
| Lint | .\gradlew.bat :app:lintDebug | PASS |
| Debug 构建 | .\gradlew.bat :app:assembleDebug | PASS |

## 功能验收
| # | 场景 | 预期 | 结果 |
|---|---|---|---|
| 1 | 首次启动 | 四 tab 显示，默认进入收件箱 | PASS — UI 层级显示全部/重要/降噪与收件箱/简报/问答/设置 |
| 2 | 未授权进入设置页 | 权限卡片显示引导并可跳系统设置 | PASS — 显示“需要通知访问权限”，按钮跳转 NotificationAccessSettingsActivity |
| 3 | 授权后返回设置页 | 权限状态刷新为已授权 | PASS — 撤销后 2 秒内显示未授权，重新授权后 2 秒内显示已授权 |
| 4 | adb 发送通知 | Room 入库并出现在收件箱 | PASS — shell 测试通知入库并可见 |
| 5 | 同一通知重复发送 | digest 去重，列表仍只有一条 | PASS — 唯一索引与 DAO 冲突测试验证；两次 adb post 的 postTime 相差 721ms，按契约应视为两条不同通知 |
| 6 | 超 100 字通知 | text 存储与展示均截断至 100 字 | PASS — Room 中最新长文本 length(text)=100 |
| 7 | 本 App 自身通知 | 不入库 | PASS — NotificationFilter 单元测试验证 self package 恒被拒绝；adb 无法伪造应用自身包名 |
| 8 | 黑名单添加包名 | 该包后续通知不入库 | PASS — com.android.shell 加入黑名单后 Blocked Test 未入库 |
| 9 | 黑名单删除包名 | 该包后续通知恢复入库 | PASS — 移除后 Offline Test 入库 |
| 10 | 已知包名规则映射 | category/importance 立即更新 | PASS — com.tencent.mm 更新为 SOCIAL / importance 2 |
| 11 | 未知包名 | 保持 UNCATEGORIZED，应用不崩溃 | PASS — shell 与未知应用保持 UNCATEGORIZED，进程存活、无 FATAL 日志 |
| 12 | 阈值 1 | 验证码在重要列表，营销折叠 | PASS — 阈值 1 时 com.tencent.mm 重要项展示，未知项折叠 |
| 13 | 阈值 3 | 低重要度也折叠 | PASS — 阈值 3 时重要度 2 社交通知进入“社交”折叠组 |
| 14 | 杀进程后重启 | Room 数据仍存在 | PASS — force-stop 后重启仍显示已入库通知 |
| 15 | 飞行模式 | W1 全部功能仍可用 | PASS — 飞行模式中 Offline Test 成功写入 Room，随后网络已恢复 |

## Notes

- 真机验收发现两个问题并已修复、回归：
  1. 未知包名旧通知消耗 classifyPending(1) 的处理额度，导致后续已知包名无法分类；
  2. 设置页通知权限状态不会跟随系统授权变化刷新。
- 验收过程产生的截图、UI dump 与数据库副本位于未跟踪的 artifacts/ 目录，已加入 .gitignore，避免提交真实通知隐私数据。
