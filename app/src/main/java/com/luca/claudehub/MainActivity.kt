package com.luca.claudehub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen hub display. Tap anywhere to talk without the wake word. */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var heardView: TextView
    private lateinit var replyView: TextView
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()

        if (Prefs.anthropicKey(this).isEmpty()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        ensurePermissionsThenStart()
    }

    override fun onResume() {
        super.onResume()
        AssistantState.listener = { render() }
        render()
    }

    override fun onPause() {
        AssistantState.listener = null
        super.onPause()
    }

    private fun buildUi() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(pad, pad * 2, pad, pad)
            setOnClickListener { talk() }
        }
        statusView = TextView(this).apply {
            textSize = 30f; setTextColor(Color.rgb(0, 200, 255)); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        heardView = TextView(this).apply {
            textSize = 18f; setTextColor(Color.GRAY); setPadding(0, pad, 0, pad / 2)
        }
        replyView = TextView(this).apply {
            textSize = 24f; setTextColor(Color.WHITE)
        }
        val spacer = android.view.View(this)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { action() }
        }.also { buttons.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }

        button("Talk") { talk() }
        toggleButton = button("Stop") { toggle() }
        button("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }

        root.addView(statusView)
        root.addView(heardView)
        root.addView(replyView)
        root.addView(spacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buttons)
        setContentView(root)
    }

    private fun render() {
        statusView.text = AssistantState.status
        heardView.text = if (AssistantState.heard.isBlank()) "" else "You: ${AssistantState.heard}"
        replyView.text = AssistantState.reply
        toggleButton.text = if (AssistantState.running) "Stop" else "Start"
    }

    private fun talk() {
        if (!AssistantState.running) { ensurePermissionsThenStart(); return }
        startService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_TALK))
    }

    private fun toggle() {
        if (AssistantState.running) stopService(Intent(this, AssistantService::class.java))
        else ensurePermissionsThenStart()
        render()
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startAssistant() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAssistant()
        } else {
            AssistantState.update(status = "Microphone permission is required")
        }
    }

    private fun startAssistant() {
        if (!AssistantState.running) startForegroundService(Intent(this, AssistantService::class.java))
    }
}
