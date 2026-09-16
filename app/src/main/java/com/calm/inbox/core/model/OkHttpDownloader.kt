package com.calm.inbox.core.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class OkHttpDownloader(private val client: OkHttpClient = OkHttpClient()) : Downloader {

    override fun download(url: String, dest: File): Flow<DownloadState> = flow {
        emit(DownloadState.Downloading(0f))
        dest.parentFile?.mkdirs()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP " + response.code + " for " + url)
            val body = response.body ?: throw IOException("empty body for " + url)
            val totalBytes = body.contentLength()
            var bytesRead = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        emit(DownloadState.Downloading(computeProgress(bytesRead, totalBytes)))
                    }
                }
            }
        }
        emit(DownloadState.Done(dest))
    }.flowOn(Dispatchers.IO).catch { e ->
        emit(DownloadState.Failed(e.message ?: "download failed: $url"))
    }

    companion object {
        const val BUFFER_SIZE = 64 * 1024

        fun computeProgress(bytesRead: Long, totalBytes: Long): Float =
            if (totalBytes <= 0L) 0f
            else (bytesRead.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}
