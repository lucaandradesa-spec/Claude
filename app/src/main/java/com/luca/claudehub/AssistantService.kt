package com.luca.claudehub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Always-on loop:  wake word "Claude" -> speech-to-text -> Claude API -> text-to-speech -> back to waiting.
 * Runs as a foreground service so Android keeps the microphone alive with the screen off.
 */
class AssistantService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_TALK = "com.luca.claudehub.TALK"
        private const val CHANNEL = "hub"
        private const val NOTIF_ID = 1
        private const val MAX_HISTORY = 20                 // messages kept in the conversation
        private const val SESSION_RESET_MS = 10 * 60_000L  // forget the conversation after 10 min idle
        // "Claude" plus words that sound identical to the recognizer; everything else maps to [unk].
        private val WAKE_WORDS = setOf("claude", "clawed", "clod")
        private val GRAMMAR = """["claude", "clawed", "clod", "[unk]"]"""
    }

    private enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private var mode = Mode.IDLE

    private var voskModel: Model? = null
    private var wakeRecognizer: Recognizer? = null
    private var wakeService: SpeechService? = null
    private var wakeAvailable = false
    private var wakeLoading = true
    private var wakeRunning = false

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var beeper: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val history = mutableListOf<JSONObject>()
    private var lastTurnAt = 0L

    // ---------- lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        AssistantState.running = true

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClaudeHub::mic").apply {
                setReferenceCounted(false); acquire()
            }
        beeper = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        tts = TextToSpeech(this, this)
        createRecognizer()
        goIdle()
        initWakeWord()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TALK) main.post { onWake() }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopWake()
        runCatching { wakeService?.shutdown() }
        runCatching { wakeRecognizer?.close() }
        runCatching { voskModel?.close() }
        recognizer?.destroy()
        tts?.stop(); tts?.shutdown()
        beeper?.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        AssistantState.running = false
        AssistantState.update(status = "Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Claude Hub", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Claude Hub")
            .setContentText("Listening for \"Claude\"")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- wake word ----------

    /** Loads the offline Vosk model (a few seconds on first launch) and sets up "Claude" spotting. */
    private fun initWakeWord() {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        Thread {
            try {
                val dir = VoskModel.ensure(this) { msg -> AssistantState.update(status = msg) }
                val model = Model(dir.absolutePath)
                val rec = Recognizer(model, 16000f, GRAMMAR)
                val service = SpeechService(rec, 16000f)
                main.post {
                    voskModel = model
                    wakeRecognizer = rec
                    wakeService = service
                    wakeAvailable = true
                    wakeLoading = false
                    if (mode == Mode.IDLE) goIdle()
                }
            } catch (e: Exception) {
                main.post {
                    wakeLoading = false
                    AssistantState.update(status = "Wake word failed (${e.message}). Tap to talk.")
                }
            }
        }.start()
    }

    private val wakeListener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = matchWake(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = matchWake(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) {}
        override fun onTimeout() {}
        override fun onError(exception: Exception?) {
            main.post {
                wakeRunning = false
                AssistantState.update(status = "Wake word error: ${exception?.message}")
            }
        }

        private fun matchWake(hypothesis: String?, field: String) {
            val text = runCatching { JSONObject(hypothesis ?: "").optString(field) }.getOrDefault("")
            if (text.split(" ").any { it in WAKE_WORDS }) {
                main.post { if (wakeRunning) onWake() }
            }
        }
    }

    private fun startWake() {
        val service = wakeService ?: return
        if (wakeRunning) return
        wakeRecognizer?.reset()
        wakeRunning = service.startListening(wakeListener)
    }

    private fun stopWake() {
        if (!wakeRunning) return
        wakeRunning = false
        runCatching { wakeService?.stop() }
    }

    private fun goIdle() {
        mode = Mode.IDLE
        startWake()
        AssistantState.update(
            status = when {
                wakeAvailable -> "Say \"Claude\""
                wakeLoading -> "Loading wake word…"
                else -> AssistantState.status
            }
        )
    }

    /** Called by the wake word or by tapping the screen. */
    private fun onWake() {
        when (mode) {
            Mode.LISTENING, Mode.THINKING -> return
            Mode.SPEAKING -> tts?.stop()           // barge-in: interrupt and listen
            Mode.IDLE -> Unit
        }
        startListening(followUp = false)
    }

    // ---------- speech to text ----------

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(recognitionListener) }
    }

    private fun startListening(followUp: Boolean) {
        stopWake()   // release the mic for the recognizer
        mode = Mode.LISTENING
        if (!followUp) beeper?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        AssistantState.update(status = if (followUp) "Listening… (go ahead)" else "Listening…", heard = "")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            val lang = Prefs.language(this@AssistantService)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            if (Prefs.autoLanguage(this@AssistantService)) {
                // honored by Google's recognizer on many devices; ignored otherwise
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(otherLanguage(lang)))
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        // short pause so the wake-word engine fully releases the microphone
        main.postDelayed({ if (mode == Mode.LISTENING) recognizer?.startListening(intent) }, 250)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            if (mode != Mode.LISTENING) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) goIdle() else think(text)
        }

        override fun onPartialResults(partial: Bundle?) {
            val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) AssistantState.update(heard = text)
        }

        override fun onError(error: Int) {
            if (mode != Mode.LISTENING) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    mode = Mode.IDLE
                    AssistantState.update(status = "Microphone permission missing")
                    return
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> createRecognizer()
            }
            goIdle()   // no speech / no match / network hiccup -> back to waiting for "Claude"
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------- Claude ----------

    private fun think(text: String) {
        mode = Mode.THINKING
        AssistantState.update(status = "Thinking…", heard = text)

        val apiKey = Prefs.anthropicKey(this)
        if (apiKey.isEmpty()) { speak("I need an Anthropic API key. Open settings to add one."); return }

        val now = System.currentTimeMillis()
        if (now - lastTurnAt > SESSION_RESET_MS) history.clear()
        lastTurnAt = now
        history.add(JSONObject().put("role", "user").put("content", text))

        val lang = Prefs.language(this)
        val system = Prefs.systemPrompt(this) +
            Memory.promptSection(this) +
            "\n\nThe hub is currently listening in " + (if (lang == "pt-BR") "Brazilian Portuguese" else "English") +
            (if (Prefs.autoLanguage(this)) ". If Luca asks to switch languages or speaks to you in the other language, call set_language and answer in that language." else ".") +
            "\n\nCurrent local date and time: " +
            SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a z", Locale.US).format(Date())
        val model = Prefs.model(this)
        val web = Prefs.webSearch(this)
        val snapshot = history.toList()

        Thread {
            try {
                val reply = ClaudeClient.ask(apiKey, model, system, snapshot, web) { name, input -> runTool(name, input) }.ifEmpty { "Sorry, I have nothing to say to that." }
                main.post {
                    history.add(JSONObject().put("role", "assistant").put("content", reply))
                    while (history.size > MAX_HISTORY) { history.removeAt(0); history.removeAt(0) }
                    lastTurnAt = System.currentTimeMillis()
                    speak(reply)
                }
            } catch (e: Exception) {
                main.post {
                    if (history.isNotEmpty()) history.removeAt(history.size - 1)
                    AssistantState.update(reply = "Error: ${e.message}")
                    speak("Sorry, I couldn't connect. Check the screen for details.", showReply = false)
                }
            }
        }.start()
    }

    /** Tools Claude can use on the phone. Runs on the network thread. */
    private fun runTool(name: String, input: JSONObject): String = when (name) {
        "remember" -> Memory.add(this, input.optString("fact"))
        "forget" -> Memory.remove(this, input.optInt("number", -1))
        "set_language" -> {
            val lang = input.optString("language")
            if (lang == "en-US" || lang == "pt-BR") {
                Prefs.setLanguage(this, lang)
                "Now listening and speaking in $lang."
            } else "Unsupported language."
        }
        else -> "Unknown tool."
    }

    private fun otherLanguage(lang: String) = if (lang == "pt-BR") "en-US" else "pt-BR"

    /** Picks the voice by what the reply is written in, so each language gets its own voice. */
    private fun voiceLanguageFor(text: String): String {
        if (!Prefs.autoLanguage(this)) return Prefs.language(this)
        val lower = " " + text.lowercase(Locale.ROOT) + " "
        val ptWords = listOf(" você ", " não ", " é ", " está ", " que ", " para ", " com ", " uma ", " isso ", " tem ", " hoje ", " amanhã ", " obrigado ", " sim ")
        val enWords = listOf(" you ", " the ", " is ", " are ", " and ", " to ", " it ", " that ", " for ", " with ", " today ", " tomorrow ", " yes ")
        var pt = ptWords.count { lower.contains(it) } + if (Regex("[ãõçáéíóúâêô]").containsMatchIn(lower)) 2 else 0
        val en = enWords.count { lower.contains(it) }
        if (pt == en) pt += if (Prefs.language(this) == "pt-BR") 1 else -1
        return if (pt > en) "pt-BR" else "en-US"
    }

    // ---------- text to speech ----------

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.setLanguage(Locale.forLanguageTag(Prefs.language(this)))
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { if (mode == Mode.SPEAKING) afterSpeaking() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { if (mode == Mode.SPEAKING) afterSpeaking() } }
        })
        ttsReady = true
    }

    private fun speak(text: String, showReply: Boolean = true) {
        mode = Mode.SPEAKING
        AssistantState.update(status = "Speaking…", reply = if (showReply) text else null)
        startWake()   // say "Claude" while it's talking to interrupt
        if (!ttsReady) { afterSpeaking(); return }
        tts?.setLanguage(Locale.forLanguageTag(voiceLanguageFor(text)))
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply-${System.currentTimeMillis()}")
    }

    private fun afterSpeaking() {
        if (Prefs.followUp(this) && Prefs.anthropicKey(this).isNotEmpty()) {
            startListening(followUp = true)   // keep the conversation going without the wake word
        } else {
            goIdle()
        }
    }
}
