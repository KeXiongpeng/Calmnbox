package com.calm.inbox.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OkHttpDownloaderTest {

    @Test
    fun computeProgressReturnsZeroWhenTotalUnknown() {
        assertThat(OkHttpDownloader.computeProgress(1024L, -1L)).isEqualTo(0f)
        assertThat(OkHttpDownloader.computeProgress(1024L, 0L)).isEqualTo(0f)
    }

    @Test
    fun computeProgressReturnsRatio() {
        assertThat(OkHttpDownloader.computeProgress(50L, 200L)).isEqualTo(0.25f)
    }

    @Test
    fun computeProgressClampsToOneWhenOver() {
        assertThat(OkHttpDownloader.computeProgress(300L, 200L)).isEqualTo(1f)
    }
}
