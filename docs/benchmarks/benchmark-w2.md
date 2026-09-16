# W2 基准记录：首 token 延迟（Qwen2.5-1.5B-Instruct int4 / MNN）

> 采集链路：MnnLlmEngine.firstTokenLatencyListener（System.nanoTime 差值，ms）→ LatencyRecorder（DataStore 持久化最近 20 条）→ 设置页「模型基准」卡片。

## 指标定义

- **首 token 延迟（ms）**：从 generateStream(prompt) 调用到第一个 token 回调的耗时。
- **目标**：< 3000 ms。
- **样本口径**：冷启动后首次推理是「权重页加载 + 首次前向」混合；验收时先做 1 次预热推理（不计入），随后连续 5 次正式测量。

## 验收协议

1. 记录设备型号、芯片、内存、Android 版本与构建类型。
2. 安装 Release 或 Debug 构建，并注明构建类型。
3. 在设置页下载模型至完成。
4. 杀掉 App 进程后冷启动，触发一次模型推理作为预热，丢弃结果。
5. 连续触发 5 次推理。
6. 打开设置页读取「模型基准（首 token 延迟）」卡片的最近 / 平均 / 样本数。
7. 可选执行 adb shell dumpsys meminfo com.calm.inbox，为 W3 Task 18 保存内存快照。
8. 将实测值填入下表并提交。

## 环境记录

| 项目 | 实测值 |
|---|---|
| 设备型号 |  |
| 芯片 |  |
| 内存 |  |
| Android 版本 |  |
| 构建类型 |  |

## 结果记录

| 推测序号 | 首 token 延迟（ms） |
|---|---|
| 预热（不计入） |  |
| 1 |  |
| 2 |  |
| 3 |  |
| 4 |  |
| 5 |  |
| 卡片最近值（ms） |  |
| 卡片平均值（ms） |  |
| 是否达标（<3000ms） |  |

## 备注

（电量、温度、后台负载等影响因子）
