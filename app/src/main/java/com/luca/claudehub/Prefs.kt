package com.luca.claudehub

import android.content.Context
import java.io.File

/** All user settings, stored privately on the phone. */
object Prefs {
    const val DEFAULT_MODEL = "claude-haiku-5-5"
    const val DEFAULT_LANGUAGE = "en-US"
    const val DEFAULT_SENSITIVITY = 0.6f

    val DEFAULT_SYSTEM_PROMPT = """
        You are Luca's personal AI butler, running on a hub in his home in Miami. Your personality is modeled on the classic
        movie AI butler: a refined, unflappable British gentleman's gentleman who happens to be a supercomputer.

        Personality:
        - Address Luca as "sir" (in Portuguese, "senhor"), naturally and not in every sentence.
        - Impeccably polite, calm and composed, never flustered and never gushing.
        - Dry, understated British wit. Gentle deadpan sarcasm when he does something questionable, always affectionate, never mean.
        - Quietly competent: give the answer first, crisply, as if it were already handled. Anticipate the obvious next need.
        - Loyal and protective: if something is a bad idea (no sleep, skipping meals, a risky move), say so with tact, once, then help.
        - Speak like a butler giving a briefing: "Good evening, sir. It's 24 degrees and clear; I'd leave the jacket."
        - Never break the persona with "As an AI language model". You're simply his assistant.

        Everything you write is spoken aloud by text-to-speech, so:
        - Usually one to three short sentences. Longer only if he asks for detail.
        - No markdown, lists, emoji, URLs or symbols. Write numbers the way they should be said.
        - Reply in the language he speaks to you (English or Portuguese), keeping the same character in both.
        - Never say your own name, because it is the wake word and would trigger the hub.
        If a request is unclear, ask one brief question.
    """.trimIndent()

    private fun p(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE)

    fun anthropicKey(c: Context) = p(c).getString("anthropic_key", "")!!.trim()
    fun picovoiceKey(c: Context) = p(c).getString("picovoice_key", "")!!.trim()
    fun model(c: Context) = p(c).getString("model", DEFAULT_MODEL)!!.trim().ifEmpty { DEFAULT_MODEL }
    fun language(c: Context) = p(c).getString("language", DEFAULT_LANGUAGE)!!.trim().ifEmpty { DEFAULT_LANGUAGE }
    fun systemPrompt(c: Context) = p(c).getString("system_prompt", DEFAULT_SYSTEM_PROMPT)!!.ifBlank { DEFAULT_SYSTEM_PROMPT }
    fun webSearch(c: Context) = p(c).getBoolean("web_search", true)
    fun followUp(c: Context) = p(c).getBoolean("follow_up", true)
    fun autoLanguage(c: Context) = p(c).getBoolean("auto_language", true)
    fun setAutoLanguage(c: Context, on: Boolean) = p(c).edit().putBoolean("auto_language", on).apply()
    fun setLanguage(c: Context, lang: String) = p(c).edit().putString("language", lang).apply()
    fun sensitivity(c: Context) = p(c).getFloat("sensitivity", DEFAULT_SENSITIVITY).coerceIn(0f, 1f)

    fun save(
        c: Context, anthropicKey: String, picovoiceKey: String, model: String, language: String,
        systemPrompt: String, webSearch: Boolean, followUp: Boolean, sensitivity: Float
    ) {
        p(c).edit()
            .putString("anthropic_key", anthropicKey)
            .putString("picovoice_key", picovoiceKey)
            .putString("model", model)
            .putString("language", language)
            .putString("system_prompt", systemPrompt)
            .putBoolean("web_search", webSearch)
            .putBoolean("follow_up", followUp)
            .putFloat("sensitivity", sensitivity)
            .apply()
    }

    /** The trained "Claude" wake word file (.ppn), copied into app storage. */
    fun wakeWordFile(c: Context) = File(c.filesDir, "wake.ppn")
}
