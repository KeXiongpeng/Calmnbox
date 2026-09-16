# CalmInbox

![Android CI](https://github.com/KeXiongpeng/Calmnbox/actions/workflows/android.yml/badge.svg)

安心收件箱是一个端侧 AI 通知管家：接管 Android 通知流，用本地模型自动分类降噪、生成每日简报，并支持基于本地通知的自然语言问答。所有通知数据与推理都留在手机上，全程零上传。

## Current status: W1 native loop

W1 builds the product foundation without any LLM dependency:

- Android single-module app with Kotlin, Jetpack Compose Material 3, Hilt, and Navigation.
- Local Room storage for notifications, daily briefs, and chat history.
- NotificationListenerService ingestion with filtering, truncation, and digest deduplication.
- Deterministic package-rule classification as the no-model fallback.
- Notification blacklist, noise threshold, and permission guidance.
- Inbox UI with category filtering and collapsed noise groups.

## Quick start

Requirements:

- JDK 17
- Android SDK Platform 34
- Android Studio Koala or newer (recommended)

Build and test:

~~~powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
~~~

Install on a connected device:

~~~powershell
.\gradlew.bat :app:installDebug
~~~

After first launch, open **Settings → Notification access** and grant CalmInbox access. W1 rule classification and W2 brief fallback work offline. Model download requires network only when explicitly used; notification content is processed only on-device.

## Release APK signing

CI builds an installable Release APK for `v*` tags. For the MVP portfolio build, that APK is signed with the Android debug key; this is suitable for real-device testing only. Replace it with a private release signing key before any store or long-term distribution.

## Local data model

CalmInbox stores all W1 data in a local Room database named `calm_inbox.db`:

- `notifications`: package, app name, title, truncated text, posted time, category, importance, summary, and a unique SHA-256 digest.
- `briefs`: daily summary date, Markdown-like content, and creation time. W2 fills this table.
- `chat_messages`: local Q&A history and citation IDs. W3 fills this table.

Notification access can be granted in **Android system Settings → Notification access → CalmInbox**.

## Privacy

CalmInbox stores notifications in a local Room database. It has no account system and no cloud model upload. W2 adds the local MNN inference path. No cloud model or account system is used.

## Roadmap

- **W1:** native notification loop and inbox
- **W2:** ModelScope model manager, MNN inference, AI classification, daily brief
- **W3:** local notification Q&A, citations, release engineering, and portfolio documentation
