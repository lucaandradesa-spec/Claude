package com.luca.claudehub

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * Makes sure the offline Vosk speech model is in app storage.
 * 1) If the APK bundles it (assets/vosk-model), copy it out once.
 * 2) Otherwise download it once (~40 MB) from alphacephei.com.
 * Call from a background thread.
 */
object VoskModel {
    private const val ASSET_DIR = "vosk-model"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    fun ensure(c: Context, progress: (String) -> Unit): File {
        val target = File(c.filesDir, ASSET_DIR)
        val marker = File(target, ".ready")
        if (marker.exists()) return target

        target.deleteRecursively()
        if (!c.assets.list(ASSET_DIR).isNullOrEmpty()) {
            progress("Preparing wake word…")
            copyAssets(c.assets, ASSET_DIR, target)
        } else {
            progress("Downloading wake word model (one time, ~40 MB)…")
            download(target, progress)
        }
        marker.createNewFile()
        return target
    }

    private fun copyAssets(am: AssetManager, path: String, dest: File) {
        val children = am.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (child in children) copyAssets(am, "$path/$child", File(dest, child))
        }
    }

    private fun download(dest: File, progress: (String) -> Unit) {
        val conn = URL(MODEL_URL).openConnection() as HttpsURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200) throw IOException("model download failed (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong
            var bytesRead = 0L
            var lastPct = -1
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0 && total > 0) {
                        bytesRead += n
                        val pct = (bytesRead * 100 / total).toInt()
                        if (pct / 10 != lastPct / 10) { lastPct = pct; progress("Downloading wake word model… $pct%") }
                    }
                    return n
                }
            }
            dest.mkdirs()
            val root = dest.canonicalPath
            ZipInputStream(counting.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // strip the top folder "vosk-model-small-en-us-0.15/"
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isEmpty()) continue
                    val out = File(dest, relative)
                    if (!out.canonicalPath.startsWith(root)) continue   // ignore unsafe paths
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
