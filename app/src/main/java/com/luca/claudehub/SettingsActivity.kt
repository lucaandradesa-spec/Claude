package com.luca.claudehub

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var anthropicKey: EditText
    private lateinit var model: EditText
    private lateinit var language: EditText
    private lateinit var systemPrompt: EditText
    private lateinit var webSearch: CheckBox
    private lateinit var followUp: CheckBox
    private lateinit var autoLanguage: CheckBox
    private lateinit var memory: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Claude Hub settings"
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun label(t: String) = col.addView(TextView(this).apply { text = t; setPadding(0, pad, 0, 0) })
        fun field(value: String, type: Int = InputType.TYPE_CLASS_TEXT, lines: Int = 1) = EditText(this).apply {
            setText(value); inputType = type
            if (lines > 1) { minLines = lines; isSingleLine = false }
            col.addView(this)
        }
        val secret = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

        label("Anthropic API key (console.anthropic.com)")
        anthropicKey = field(Prefs.anthropicKey(this), secret)

        label("Model (e.g. claude-haiku-5-5 = fastest, claude-sonnet-5-5 = smarter)")
        model = field(Prefs.model(this))

        label("Speech language (en-US or pt-BR) — you can also just say \"switch to Portuguese\"")
        language = field(Prefs.language(this))

        label("Personality / instructions")
        systemPrompt = field(Prefs.systemPrompt(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 6)

        webSearch = CheckBox(this).apply { text = "Allow web search (weather, news, scores…)"; isChecked = Prefs.webSearch(this@SettingsActivity) }
        followUp = CheckBox(this).apply { text = "Keep listening after each answer (no wake word for follow-ups)"; isChecked = Prefs.followUp(this@SettingsActivity) }
        autoLanguage = CheckBox(this).apply { text = "Auto-switch English / Portuguese (voice follows the reply's language)"; isChecked = Prefs.autoLanguage(this@SettingsActivity) }
        col.addView(webSearch); col.addView(followUp); col.addView(autoLanguage)

        label("Memory — what Claude remembers about you (one fact per line; edit or delete freely)")
        memory = field(Memory.all(this).joinToString("\n"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 5)

        col.addView(Button(this).apply { text = "Save"; setOnClickListener { save() } })
        col.addView(Button(this).apply { text = "Reset personality to default"; setOnClickListener { systemPrompt.setText(Prefs.DEFAULT_SYSTEM_PROMPT) } })
        col.addView(Button(this).apply { text = "Disable battery optimization (recommended)"; setOnClickListener { askBatteryExemption() } })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun save() {
        Prefs.save(
            this,
            anthropicKey.text.toString().trim(),
            Prefs.picovoiceKey(this),
            model.text.toString().trim(),
            language.text.toString().trim(),
            systemPrompt.text.toString(),
            webSearch.isChecked,
            followUp.isChecked,
            Prefs.sensitivity(this)
        )
        Prefs.setAutoLanguage(this, autoLanguage.isChecked)
        Memory.setAll(this, memory.text.toString().lines())
        // Restart the assistant so new keys / wake word take effect.
        val svc = Intent(this, AssistantService::class.java)
        stopService(svc)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startForegroundService(svc)
        }
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already exempt", Toast.LENGTH_SHORT).show(); return
        }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }
}
