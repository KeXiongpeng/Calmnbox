package com.calm.inbox.core.classify

import com.calm.inbox.core.database.Category
import com.calm.inbox.core.database.entity.NotificationEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleEngineTest {

    private fun item(pkg: String) = NotificationEntity(
        packageName = pkg,
        appName = "App",
        title = "Title",
        text = "Text",
        postedAt = 1_000L,
        digest = "digest"
    )

    @Test
    fun defaultPackageMapCoversAtLeastThirtyApps() {
        assertThat(RuleEngine.DEFAULT_PACKAGE_MAP.size).isAtLeast(30)
    }

    @Test
    fun classifiesKnownPackageAndUsesFixedImportance() {
        val result = RuleEngine().classify(item("com.eg.android.AlipayGphone"))

        assertThat(result).isEqualTo(RuleResult(Category.FINANCE, 4))
    }

    @Test
    fun unknownPackageReturnsNull() {
        assertThat(RuleEngine().classify(item("unknown.pkg"))).isNull()
    }

    @Test
    fun customMapOverridesDefaultOnlyForInjectedMap() {
        val engine = RuleEngine(mapOf("custom.app" to Category.WORK))

        assertThat(engine.classify(item("custom.app"))?.category).isEqualTo(Category.WORK)
        assertThat(engine.classify(item("com.eg.android.AlipayGphone"))).isNull()
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
}
