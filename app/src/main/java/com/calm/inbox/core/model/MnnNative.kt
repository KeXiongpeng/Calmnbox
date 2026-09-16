package com.calm.inbox.core.model

object MnnNative {
    init {
        System.loadLibrary("calm_mnn")
    }

    external fun create(configPath: String): Long
    external fun generate(ptr: Long, prompt: String, listener: StreamListener)
    external fun release(ptr: Long)

    interface StreamListener {
        fun onToken(token: String?): Boolean
    }
}
