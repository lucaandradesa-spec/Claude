package com.luca.claudehub

import android.os.Handler
import android.os.Looper

/** Shared state between the background service and the screen. */
object AssistantState {
    @Volatile var running = false
    @Volatile var status = "Stopped"
    @Volatile var heard = ""
    @Volatile var reply = ""

    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun update(status: String? = null, heard: String? = null, reply: String? = null) {
        status?.let { this.status = it }
        heard?.let { this.heard = it }
        reply?.let { this.reply = it }
        main.post { listener?.invoke() }
    }
}
