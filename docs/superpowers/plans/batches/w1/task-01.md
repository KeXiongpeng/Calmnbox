### Task 1: 项目脚手架与版本目录（:app + Hilt + NavHost）

**前置依赖：** 空仓库，仅设计文档与计划文档；开发机已安装 JDK 17、Android SDK 34、Android Studio。

## Files

**Create:**
- settings.gradle.kts
- build.gradle.kts
- gradle.properties
- gradle/libs.versions.toml
- app/build.gradle.kts
- app/proguard-rules.pro
- app/src/main/AndroidManifest.xml
- app/src/main/java/com/calm/inbox/CalmInboxApp.kt
- app/src/main/java/com/calm/inbox/MainActivity.kt
- app/src/main/java/com/calm/inbox/ui/theme/Theme.kt
- app/src/main/java/com/calm/inbox/AppNavHost.kt
- app/src/main/res/values/colors.xml
- app/src/main/res/values/strings.xml
- app/src/main/res/values/themes.xml
- app/src/main/res/drawable/ic_launcher_foreground.xml
- app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
- app/src/test/java/com/calm/inbox/VersionCatalogContractTest.kt
- .gitignore
- README.md

**Modify:** 无

## Interfaces

**Produces:**

~~~kotlin
package com.calm.inbox

@HiltAndroidApp
class CalmInboxApp : Application()
~~~

~~~kotlin
package com.calm.inbox

@AndroidEntryPoint
class MainActivity : ComponentActivity()
~~~

~~~kotlin
package com.calm.inbox

@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier)
// Routes, exact strings only: "inbox", "brief", "chat", "settings"
~~~

**Consumes:** 无。后续所有任务消费本任务产出的 Gradle 版本目录、Hilt Application、MainActivity 与四个导航路由。

## Step 1: 建立最小 Gradle 骨架并写失败测试

1. 运行 gradle wrapper --gradle-version 8.9 生成 gradlew.bat、gradlew、gradle/wrapper 目录；wrapper 文件必须进入 git。
2. 创建 app/src/test/java/com/calm/inbox/VersionCatalogContractTest.kt：

~~~kotlin
package com.calm.inbox

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class VersionCatalogContractTest {

    private fun root(path: String): File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?.let { File(it, path) }
            ?: throw IllegalStateException("project root not found")

    @Test
    fun versionCatalogUsesLockedProjectBaseline() {
        val text = root("gradle/libs.versions.toml").readText()
        assertThat(text).contains("agp = \"8.5.2\"")
        assertThat(text).contains("kotlin = \"2.0.20\"")
        assertThat(text).contains("hilt = \"2.52\"")
        assertThat(text).contains("room = \"2.6.1\"")
        assertThat(text).contains("work = \"2.9.1\"")
        assertThat(text).contains("datastore = \"1.1.1\"")
        assertThat(text).contains("composeBom = \"2024.09.03\"")
    }

    @Test
    fun appModuleUsesSingleModuleApplicationId() {
        val text = root("app/build.gradle.kts").readText()
        assertThat(text).contains("namespace = \"com.calm.inbox\"")
        assertThat(text).contains("applicationId = \"com.calm.inbox\"")
        assertThat(text).contains("compileSdk = 34")
        assertThat(text).contains("minSdk = 26")
        assertThat(text).contains("targetSdk = 34")
    }
}
~~~

3. 运行：

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.VersionCatalogContractTest"
~~~

**Expected:** Gradle 报 project directory is not part of this build 或测试源目录不存在；不要伪造通过。

## Step 2: 写最小实现

创建 settings.gradle.kts：

~~~kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CalmInbox"
include(":app")
~~~

创建 build.gradle.kts：

~~~kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
}
~~~

创建 gradle.properties：

~~~properties
org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
~~~

创建 gradle/libs.versions.toml：

~~~toml
[versions]
agp = "8.5.2"
kotlin = "2.0.20"
coreKtx = "1.13.1"
lifecycle = "2.8.6"
activityCompose = "1.9.2"
composeBom = "2024.09.03"
navigationCompose = "2.8.0"
hilt = "2.52"
hiltNavigationCompose = "1.2.0"
hiltWork = "1.2.0"
room = "2.6.1"
work = "2.9.1"
datastore = "1.1.1"
okhttp = "4.12.0"
junit = "4.13.2"
robolectric = "4.13"
androidxTestCore = "1.6.1"
coroutinesTest = "1.8.1"
turbine = "1.1.0"
truth = "1.4.4"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
material3 = { group = "androidx.compose.material3", name = "material3" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "hiltWork" }
androidx-hilt-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "hiltWork" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
work-testing = { group = "androidx.work", name = "work-testing", version.ref = "work" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTestCore" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutinesTest" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
truth = { group = "com.google.truth", name = "truth", version.ref = "truth" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-kapt = { id = "org.jetbrains.kotlin.kapt", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
~~~

创建 app/build.gradle.kts：

~~~kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.calm.inbox"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.calm.inbox"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    kapt(libs.hilt.compiler)
    kapt(libs.androidx.hilt.compiler)
    kapt(libs.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.work.testing)
}
~~~

创建 AndroidManifest.xml：

~~~xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name=".CalmInboxApp"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.CalmInbox">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
~~~

创建 CalmInboxApp.kt：

~~~kotlin
package com.calm.inbox

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CalmInboxApp : Application()
~~~

创建 MainActivity.kt：

~~~kotlin
package com.calm.inbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.calm.inbox.ui.theme.CalmInboxTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CalmInboxTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost()
                }
            }
        }
    }
}
~~~

创建 res 资源：

~~~xml
<!-- app/src/main/res/values/strings.xml -->
<resources>
    <string name="app_name">CalmInbox</string>
    <string name="notification_listener_label">CalmInbox 通知整理</string>
</resources>
~~~

~~~xml
<!-- app/src/main/res/values/colors.xml -->
<resources>
    <color name="brand_primary">#3F6C51</color>
    <color name="launcher_background">#F3FBF5</color>
</resources>
~~~

~~~xml
<!-- app/src/main/res/values/themes.xml -->
<resources>
    <style name="Theme.CalmInbox" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
~~~

~~~xml
<!-- app/src/main/res/drawable/ic_launcher_foreground.xml -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#3F6C51"
        android:pathData="M30,38h48v26h-30v8l-12,-10h-6z" />
    <path
        android:fillColor="#F3FBF5"
        android:pathData="M42,47h24v3h-24zM42,54h16v3h-16z" />
</vector>
~~~

~~~xml
<!-- app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
~~~

minSdk 26 使 adaptive icon 可作为唯一 launcher 图标，不需要 legacy PNG。Task 3 引用的 notification_listener_label 已在此定义。创建 ui/theme/Theme.kt：Material 3 lightColorScheme、darkColorScheme、CalmInboxTheme；动态取色仅在 SDK 31 及以上启用，不新增依赖。

创建 AppNavHost.kt。四个准确路由与底部导航必须立即存在；每个目的地先用简单 Text 显示归属任务，Task 2~6 逐个替换，不允许改路由名。核心结构：

~~~kotlin
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    Scaffold(bottomBar = { CalmBottomBar(navController) }) { padding ->
        NavHost(navController = navController, startDestination = "inbox", modifier = Modifier.padding(padding)) {
            composable("inbox") { RouteScaffold("inbox", "Task 6") }
            composable("brief") { RouteScaffold("brief", "W2 Task 13") }
            composable("chat") { RouteScaffold("chat", "W3 Task 15") }
            composable("settings") { RouteScaffold("settings", "Task 5") }
        }
    }
}

private fun NavHostController.navigateSingleTop(route: String) = navigate(route) {
    launchSingleTop = true
    popUpTo("inbox") { saveState = true }
    restoreState = true
}
~~~

CalmBottomBar 依次展示收件箱、简报、问答、设置，并调用 navigateSingleTop；图标用 Material Icons Outlined 的 Inbox、Summarize、Chat、Settings。

创建 .gitignore，至少包含 .gradle/、build/、local.properties、.idea/、*.iml、.DS_Store、captures/。创建 README.md，内容包含项目一句话、W1 目标、克隆后构建命令、通知监听权限说明与零上传隐私承诺；这是正式基线文档，后续任务逐步扩展。

## Step 3: 验证

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.VersionCatalogContractTest"
.\gradlew.bat :app:assembleDebug
~~~

**Expected:** 两项均 BUILD SUCCESSFUL；APK 位于 app/build/outputs/apk/debug/app-debug.apk。

## Step 4: Commit

~~~powershell
git add .gitignore README.md settings.gradle.kts build.gradle.kts gradle.properties gradle gradlew.bat gradlew app/build.gradle.kts app/proguard-rules.pro app/src/main app/src/test
git commit -m "build: scaffold single-module CalmInbox Android app"
~~~
