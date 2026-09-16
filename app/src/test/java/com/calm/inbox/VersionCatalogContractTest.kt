package com.calm.inbox

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class VersionCatalogContractTest {

    private fun root(path: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
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
