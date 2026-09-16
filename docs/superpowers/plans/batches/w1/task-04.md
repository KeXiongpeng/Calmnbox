### Task 4: 规则分类引擎（包名映射 + 重要度）

**前置依赖：** Task 2 的 Category 与 NotificationEntity；Task 3 的通知入库链路。

## Files

**Create:**
- app/src/main/java/com/calm/inbox/core/classify/RuleEngine.kt
- app/src/test/java/com/calm/inbox/core/classify/RuleEngineTest.kt

**Modify:**
- app/src/main/java/com/calm/inbox/di/AppModule.kt

## Interfaces

**Produces（骨架契约逐字遵守）：**

~~~kotlin
data class RuleResult(val category: Category, val importance: Int)

class RuleEngine(private val packageMap: Map<String, Category> = DEFAULT_PACKAGE_MAP) {
    fun classify(item: NotificationEntity): RuleResult?
    companion object {
        val DEFAULT_PACKAGE_MAP: Map<String, Category>
        fun importanceOf(category: Category): Int
    }
}
~~~

**Consumes:** NotificationEntity.packageName；Task 11 的 HybridClassifier 将直接消费 classify(item) 与 importanceOf(category)。

## Step 1: 写失败测试

RuleEngineTest 必须包含以下断言：

~~~kotlin
@Test
fun defaultPackageMapCoversAtLeastThirtyApps() {
    assertThat(RuleEngine.DEFAULT_PACKAGE_MAP.size).isAtLeast(30)
}

@Test
fun classifiesKnownPackageAndUsesFixedImportance() {
    val engine = RuleEngine()
    val result = engine.classify(item(pkg = "com.eg.android.AlipayGphone"))
    assertThat(result).isEqualTo(RuleResult(Category.FINANCE, 4))
}

@Test
fun unknownPackageReturnsNull() {
    assertThat(RuleEngine().classify(item(pkg = "unknown.pkg"))).isNull()
}

@Test
fun customMapOverridesDefaultOnlyForInjectedMap() {
    val engine = RuleEngine(mapOf("custom.app" to Category.WORK))
    assertThat(engine.classify(item(pkg = "custom.app"))?.category).isEqualTo(Category.WORK)
}

@Test
fun importanceValuesMatchSpec() {
    assertThat(RuleEngine.importanceOf(Category.VERIFICATION)).isEqualTo(5)
    assertThat(RuleEngine.importanceOf(Category.EXPRESS)).isEqualTo(4)
    assertThat(RuleEngine.importanceOf(Category.FINANCE)).isEqualTo(4)
    assertThat(RuleEngine.importanceOf(Category.WORK)).isEqualTo(4)
    assertThat(RuleEngine.importanceOf(Category.SOCIAL)).isEqualTo(2)
    assertThat(RuleEngine.importanceOf(Category.SHOPPING)).isEqualTo(2)
    assertThat(RuleEngine.importanceOf(Category.SYSTEM)).isEqualTo(2)
    assertThat(RuleEngine.importanceOf(Category.OTHER)).isEqualTo(2)
    assertThat(RuleEngine.importanceOf(Category.MARKETING)).isEqualTo(1)
    assertThat(RuleEngine.importanceOf(Category.UNCATEGORIZED)).isEqualTo(0)
}
~~~

测试 helper 构造完整 NotificationEntity，digest 可为固定字符串，因为规则引擎只读 packageName。

## Step 2: 确认失败

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.classify.RuleEngineTest"
~~~

**Expected:** Unresolved reference RuleEngine。

## Step 3: 最小实现

~~~kotlin
package com.calm.inbox.core.classify

import com.calm.inbox.core.database.Category
import com.calm.inbox.core.database.entity.NotificationEntity

data class RuleResult(val category: Category, val importance: Int)

class RuleEngine(
    private val packageMap: Map<String, Category> = DEFAULT_PACKAGE_MAP,
) {
    fun classify(item: NotificationEntity): RuleResult? =
        packageMap[item.packageName]?.let { RuleResult(it, importanceOf(it)) }

    companion object {
        val DEFAULT_PACKAGE_MAP: Map<String, Category> = mapOf(
            "com.eg.android.AlipayGphone" to Category.FINANCE,
            "com.tencent.mm" to Category.SOCIAL,
            "com.tencent.mobileqq" to Category.SOCIAL,
            "com.alibaba.android.rimet" to Category.WORK,
            "com.taobao.taobao" to Category.SHOPPING,
            "com.tmall.wireless" to Category.SHOPPING,
            "com.jd.jrapp" to Category.SHOPPING,
            "com.jingdong.app.mall" to Category.SHOPPING,
            "com.xunmeng.pinduoduo" to Category.SHOPPING,
            "com.sankuai.meituan" to Category.SHOPPING,
            "com.sankuai.meituan.takeoutnew" to Category.SHOPPING,
            "com.dianping.so33" to Category.SHOPPING,
            "me.ele" to Category.SHOPPING,
            "com.sfbest.sunline" to Category.EXPRESS,
            "com.sf.sfww" to Category.EXPRESS,
            "com.zto.express" to Category.EXPRESS,
            "com.ytoexpress.package" to Category.EXPRESS,
            "com.sto.express" to Category.EXPRESS,
            "com.yunda.express" to Category.EXPRESS,
            "cn.jiguang.dailydeals" to Category.MARKETING,
            "com.ss.android.ugc.aweme" to Category.SOCIAL,
            "com.smile.gifmaker" to Category.SOCIAL,
            "com.tencent.qqlive" to Category.SOCIAL,
            "com.netease.cloudmusic" to Category.SOCIAL,
            "com.weico.international" to Category.SOCIAL,
            "com.baidu.BaiduMap" to Category.SYSTEM,
            "com.autonavi.minimap" to Category.SYSTEM,
            "com.UCMobile" to Category.SYSTEM,
            "com.quark.browser" to Category.SYSTEM,
            "com.icbc" to Category.FINANCE,
            "com.chinamworld.main" to Category.FINANCE,
            "cmb.pb" to Category.FINANCE,
            "com.cmbchina.ccd.pluto.cmbActivity" to Category.FINANCE,
            "com.android.bankcomm" to Category.FINANCE,
            "com.android.chrome" to Category.SYSTEM,
            "com.miui.securitycenter" to Category.SYSTEM
        )

        fun importanceOf(category: Category): Int = when (category) {
            Category.VERIFICATION -> 5
            Category.EXPRESS, Category.FINANCE, Category.WORK -> 4
            Category.SOCIAL, Category.SHOPPING, Category.SYSTEM, Category.OTHER -> 2
            Category.MARKETING -> 1
            Category.UNCATEGORIZED -> 0
        }
    }
}
~~~

> 该映射包含 36 个国内常见包名，已满足 ≥30 的骨架要求。若真机验证发现某个包名与当前市场包不一致，只修正包名字符串，不改 Category 值。

AppModule 追加：

~~~kotlin
@Provides
@Singleton
fun provideRuleEngine(): RuleEngine = RuleEngine()
~~~

## Step 4: 确认通过

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.classify.RuleEngineTest"
.\gradlew.bat :app:testDebugUnitTest
~~~

**Expected:** RuleEngineTest 5 个用例通过；既有测试无回归。

## Step 5: Commit

~~~powershell
git add app/src/main/java/com/calm/inbox/core/classify/RuleEngine.kt app/src/main/java/com/calm/inbox/di/AppModule.kt app/src/test/java/com/calm/inbox/core/classify/RuleEngineTest.kt
git commit -m "feat(classify): add package rule engine with deterministic importance"
~~~
