### Task 17: CI 流水线（GitHub Actions 构建 + lint + test + Release APK）

**前置依赖：** Task 1 Gradle wrapper、Task 16 全量回归通过。

## Files

**Create:**
- .github/workflows/android.yml

**Modify:**
- app/build.gradle.kts
- README.md

## Interfaces

**Produces:** push/pull request 自动验证；打 v* tag 自动创建 GitHub Release 并附可安装 APK。

**Consumes:** Gradle wrapper、Android SDK 34、GitHub Actions runner、GitHub Releases。

## Step 1: 写失败验证

先在本地确认当前分支可构建：

~~~powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
~~~

若本地失败，先修复再提交 workflow。创建 workflow 后，不 push 前无法得到远端失败日志；本地必须作为第一道门禁。

## Step 2: 实现 release 可安装签名

MVP 不引入私有签名密钥。修改 app/build.gradle.kts 的 android.buildTypes.release：

~~~kotlin
release {
    isMinifyEnabled = false
    signingConfig = signingConfigs.getByName("debug")
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
~~~

README 的发布说明必须明确：v1.0.0 Release APK 使用 debug key 签名，仅用于作品集真机体验；商店发布前需替换私有签名。

## Step 3: 写 workflow

创建 .github/workflows/android.yml：

~~~yaml
name: Android CI

on:
  push:
    branches: ['**']
    tags: ['v*']
  pull_request:

permissions:
  contents: write

jobs:
  verify:
    name: Lint, test, and build debug APK
    runs-on: ubuntu-latest
    steps:
      - name: Check out
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Grant Gradle wrapper execute permission
        run: chmod +x ./gradlew

      - name: Lint
        run: ./gradlew lintDebug

      - name: Unit test
        run: ./gradlew testDebugUnitTest

      - name: Assemble debug APK
        run: ./gradlew assembleDebug

      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: calm-inbox-debug
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error

  release:
    name: Attach release APK
    needs: verify
    if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    steps:
      - name: Check out
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Grant Gradle wrapper execute permission
        run: chmod +x ./gradlew

      - name: Assemble release APK
        run: ./gradlew assembleRelease

      - name: Upload release APK
        uses: actions/upload-artifact@v4
        with:
          name: calm-inbox-release
          path: app/build/outputs/apk/release/app-release.apk
          if-no-files-found: error

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: app/build/outputs/apk/release/app-release.apk
          generate_release_notes: true
~~~

README 增加 CI 徽章模板，远端仓库名确定后把 OWNER/REPO 替换为实际值：

~~~markdown
![Android CI](https://github.com/OWNER/REPO/actions/workflows/android.yml/badge.svg)
~~~

## Step 4: 验证

本地：

~~~powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
~~~

远端：
1. push 分支，打开 Actions 页面确认 Android CI 成功；
2. 确认 debug artifact 存在且大小合理；
3. 创建测试 tag v0.3.0-ci 并 push：
   git tag v0.3.0-ci
   git push origin v0.3.0-ci
4. 确认 release job 成功、GitHub Release 生成、APK 可下载；
5. 下载 APK 安装到真机并确认签名可安装；
6. 删除远端测试 tag 与测试 Release，避免污染正式版本列表。

**Expected:** 所有步骤成功，正式 v1.0.0 发布前 CI 已被一次 tag 全链路验证。

## Step 5: Commit

~~~powershell
git add .github/workflows/android.yml app/build.gradle.kts README.md
git commit -m "build: add Android CI lint, tests, and tagged release APK workflow"
~~~
