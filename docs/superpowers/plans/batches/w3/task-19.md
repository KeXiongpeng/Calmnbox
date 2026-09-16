### Task 19: 发布与准确率评估（50 条抽样 + v1.0.0 + 最终验收）

**前置依赖：** Task 18 文档与基准完成；App 已连续真机使用至少 3 天，通知库具备足够样本。

## Files

**Create:**
- scripts/export_classification_sample.py
- scripts/compute_classification_accuracy.py
- docs/evaluations/classification-accuracy-v1.md
- docs/acceptance/final-release-checklist.md

**Modify:**
- app/build.gradle.kts
- README.md

## Interfaces

**Produces:**
- 50 条固定随机种子的分类评估样本；
- 人工核对后的准确率结果；
- v1.0.0 版本号与 Release；
- 最终验收记录。

**Consumes:** Debug 构建可被 run-as 访问的 calm_inbox.db；W2 分类结果；Task 18 benchmark-final.md。

## Step 1: 写并验证评估脚本

创建 scripts/export_classification_sample.py：

~~~python
#!/usr/bin/env python3
import argparse
import csv
import random
import sqlite3
from pathlib import Path

FIELDS = [
    "notification_id", "package_name", "app_name", "title", "text",
    "predicted_category", "predicted_importance",
    "human_category", "human_importance", "is_correct",
]

def export(database: Path, output: Path, sample_size: int, seed: int) -> int:
    connection = sqlite3.connect(database)
    try:
        rows = connection.execute(
            """
            SELECT id, packageName, appName, title, text, category, importance
            FROM notifications
            WHERE category != 'UNCATEGORIZED'
            ORDER BY id
            """
        ).fetchall()
    finally:
        connection.close()

    if len(rows) < sample_size:
        raise SystemExit(
            f"Only {len(rows)} classified notifications available; need {sample_size}. "
            "Collect more real notifications before evaluation."
        )

    random.Random(seed).shuffle(rows)
    with output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        for row in rows[:sample_size]:
            writer.writerow({
                "notification_id": row[0],
                "package_name": row[1],
                "app_name": row[2],
                "title": row[3],
                "text": row[4],
                "predicted_category": row[5],
                "predicted_importance": row[6],
                "human_category": "",
                "human_importance": "",
                "is_correct": "",
            })
    return sample_size

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("database", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--sample-size", type=int, default=50)
    parser.add_argument("--seed", type=int, default=20260916)
    args = parser.parse_args()
    count = export(args.database, args.output, args.sample_size, args.seed)
    print(f"Wrote {count} sampled notifications to {args.output}")

if __name__ == "__main__":
    main()
~~~

创建 scripts/compute_classification_accuracy.py：

~~~python
#!/usr/bin/env python3
import argparse
import csv
from collections import Counter
from pathlib import Path

def compute(path: Path) -> tuple[int, int, float]:
    total = 0
    correct = 0
    with path.open(newline="", encoding="utf-8") as handle:
        for index, row in enumerate(csv.DictReader(handle), start=2):
            human = row["human_category"].strip().upper()
            if not human:
                raise SystemExit(f"Line {index}: human_category is empty")
            row["is_correct"] = "yes" if human == row["predicted_category"].strip().upper() else "no"
            total += 1
            correct += row["is_correct"] == "yes"
    if total == 0:
        raise SystemExit("No evaluated rows")
    return correct, total, correct / total

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("csv", type=Path)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    correct, total, accuracy = compute(args.csv)
    print(f"Correct: {correct}/{total}")
    print(f"Accuracy: {accuracy:.1%}")
    if args.write:
        with args.csv.open(newline="", encoding="utf-8") as source:
            rows = list(csv.DictReader(source))
        with args.csv.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)

if __name__ == "__main__":
    main()
~~~

本地脚本自检：构造 5 行临时 SQLite 或用已知 Room 数据运行，确认样本不足时报错、50 条输出固定、空人工列会被 accuracy 脚本拒绝。

## Step 2: 导出真实样本并人工核对

1. 确保手机安装 Debug 构建，通知访问已授权，分类已运行。
2. 导出数据库：

~~~powershell
adb shell run-as com.calm.inbox ls databases
adb shell run-as com.calm.inbox cp databases/calm_inbox.db /data/local/tmp/calm_inbox.db
adb shell run-as com.calm.inbox cp databases/calm_inbox.db-wal /data/local/tmp/calm_inbox.db-wal
adb shell run-as com.calm.inbox cp databases/calm_inbox.db-shm /data/local/tmp/calm_inbox.db-shm
adb pull /data/local/tmp/calm_inbox.db build/calm_inbox.db
adb shell rm /data/local/tmp/calm_inbox.db /data/local/tmp/calm_inbox.db-wal /data/local/tmp/calm_inbox.db-shm
~~~

3. 导出固定样本：

~~~powershell
python scripts/export_classification_sample.py build/calm_inbox.db build/classification-sample.csv --sample-size 50 --seed 20260916
~~~

4. 人工逐行核对 human_category，只允许骨架十类：UNCATEGORIZED、VERIFICATION、EXPRESS、FINANCE、SOCIAL、WORK、SHOPPING、SYSTEM、MARKETING、OTHER。human_importance 为 1~5。
5. 计算：

~~~powershell
python scripts/compute_classification_accuracy.py build/classification-sample.csv --write
~~~

6. 创建 docs/evaluations/classification-accuracy-v1.md，记录：
- 抽样命令与 seed；
- 50 条结果 CSV 副本路径 build/classification-sample.csv；
- 正确数/总数；
- 准确率；
- 每类混淆统计；
- 5 个典型错误与改进判断；
- 结论是否达到 ≥80%。

混淆统计可用 Python Counter 快速计算：

~~~powershell
python -c "import csv,collections; rows=list(csv.DictReader(open('build/classification-sample.csv',encoding='utf-8'))); print(collections.Counter((r['predicted_category'],r['human_category']) for r in rows if r['is_correct']=='no'))"
~~~

## Step 3: 版本与最终验收

修改 app/build.gradle.kts：
- versionCode = 3；
- versionName = "1.0.0"。

创建 docs/acceptance/final-release-checklist.md：

~~~markdown
# CalmInbox v1.0.0 final release checklist

## Engineering
| Item | Requirement | Result |
|---|---|---|
| Clean build | .\gradlew.bat clean |  |
| Unit tests | :app:testDebugUnitTest |  |
| Lint | :app:lintDebug |  |
| Debug APK | :app:assembleDebug |  |
| Release APK | :app:assembleRelease |  |
| CI branch run | Android CI green |  |
| Tag CI run | v1.0.0 release job green |  |

## Product
| Item | Requirement | Result |
|---|---|---|
| Notification access | New install can grant and ingest |  |
| No-model mode | Rule classification and inbox still usable |  |
| Model download | ModelScope progress and delete work |  |
| Classification | 50-sample accuracy >= 80% |  |
| Daily brief | 22:00 generation, retry is idempotent |  |
| Chat citation | Answer cites and jumps to source |  |
| OOM recovery | Engine releases and can retry |  |
| Idle release | Engine releases after 10 minutes |  |
| First token | Average < 3000 ms |  |
| Memory | No frequent kill during 5 generations |  |
| Privacy | No cloud upload, no sensitive logging |  |

## Release
| Item | Requirement | Result |
|---|---|---|
| Release APK | Uploaded to GitHub Release |  |
| Install smoke | Fresh device installs and opens |  |
| README | GIF, architecture, benchmarks, privacy, English guide |  |
| Reproducibility | Clean clone opens in Android Studio |  |
| Evaluation | classification-accuracy-v1.md completed |  |
~~~

执行：

~~~powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
git diff --check
~~~

## Step 4: 发布 v1.0.0

~~~powershell
git add app/build.gradle.kts scripts docs/evaluations/classification-accuracy-v1.md docs/acceptance/final-release-checklist.md README.md
git commit -m "chore(release): prepare CalmInbox v1.0.0 with 50-sample evaluation"
git tag -a v1.0.0 -m "CalmInbox v1.0.0 on-device AI notification inbox"
git push origin main
git push origin v1.0.0
~~~

等待 Actions 完成，然后：
1. 下载 Release APK；
2. 在已卸载 App 的真机安装；
3. 重新授权、下载模型、完成一次问答；
4. 回填 final-release-checklist.md；
5. 若发现阻塞性问题，删除未对外使用的 v1.0.0 tag 与 Release，修复后重新打 tag；不要在损坏版本上追加补丁说明；
6. 最终提交：

~~~powershell
git add docs/acceptance/final-release-checklist.md
git commit -m "test: record v1.0.0 final release acceptance"
git push origin main
~~~

## Step 5: Success Criteria

只有以下条件全部为真才允许宣布完成：
- 50 条人工核对分类准确率 ≥80%；
- 首 token 平均延迟 <3s；
- 5 次连续问答期间 App 未被系统频繁杀死；
- clone 后 Android Studio 可直接构建运行；
- push CI 与 tag Release 全绿；
- README 中英文、GIF、架构、隐私、基准、AI 工作流完整；
- 本地数据零上传路径已由代码与隐私文档共同说明。
