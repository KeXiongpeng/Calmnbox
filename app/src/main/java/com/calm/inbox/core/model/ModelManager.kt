package com.calm.inbox.core.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

open class ModelManager(
    private val context: Context,
    private val downloader: Downloader
) {
    fun modelDir(): File = File(context.filesDir, "models/$MODEL_DIR_NAME")

    open fun isModelReady(): Boolean {
        val dir = modelDir()
        if (!dir.isDirectory) return false
        if (!File(dir, "config.json").isFile) return false
        val totalBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return totalBytes > MIN_MODEL_BYTES
    }

    open fun downloadModel(): Flow<DownloadState> = flow {
        if (isModelReady()) {
            emit(DownloadState.Done(modelDir()))
            return@flow
        }

        val dir = modelDir()
        dir.mkdirs()
        val total = MODEL_FILES.size
        for ((index, name) in MODEL_FILES.withIndex()) {
            var failure: String? = null
            var fileDone = false
            downloader.download("$MODEL_BASE_URL/$name", File(dir, name)).collect { state ->
                when (state) {
                    is DownloadState.Downloading ->
                        emit(DownloadState.Downloading((index + state.progress) / total))
                    is DownloadState.Done -> fileDone = true
                    is DownloadState.Failed -> failure = state.message
                    DownloadState.Idle -> Unit
                }
            }

            val failureMessage = failure
            if (failureMessage != null) {
                emit(DownloadState.Failed(failureMessage))
                return@flow
            }
            if (!fileDone) {
                emit(DownloadState.Failed("download interrupted: $name"))
                return@flow
            }
        }
        emit(DownloadState.Done(dir))
    }.flowOn(Dispatchers.IO)

    open suspend fun deleteModel() {
        modelDir().deleteRecursively()
    }

    companion object {
        const val MODEL_DIR_NAME = "Qwen2.5-1.5B-Instruct-MNN"
        const val MIN_MODEL_BYTES = 500L * 1024 * 1024
        val MODEL_FILES = listOf(
            "config.json",
            "llm_config.json",
            "llm.mnn",
            "llm.mnn.weight",
            "tokenizer.mtok"
        )
        const val MODEL_BASE_URL =
            "https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master"
    }
}
