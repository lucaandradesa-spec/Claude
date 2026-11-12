# Claude Hub — "Jarvis" on a Galaxy Note 10

Say **"Claude"** → beep → ask anything → Claude answers out loud → follow-ups don't need the wake word.
Runs 24/7 as a foreground service; the wake word is detected on-device (Vosk), so audio
only leaves the phone after you say "Claude".

## 1. Get an API key
**Anthropic API key**: console.anthropic.com → "Em outro lugar / Elsewhere" → create key. Add a few dollars of
credit and set a monthly spend limit.

## 2. Wake word
Nothing to set up. "Claude" is detected offline with Vosk (free, open source). The English speech model is
downloaded automatically by the GitHub build and bundled in the APK.

## 3. Build & install the app
**Option A — Android Studio (any computer):**
1. Install Android Studio → *Open* this `ClaudeHub` folder → wait for Gradle sync.
2. On the Note 10: Settings → About phone → Software information → tap *Build number* 7× →
   back to Settings → Developer options → enable **USB debugging**.
3. Plug the phone in, allow the prompt, press ▶ Run.

**Option B — no Android Studio (GitHub builds it):** push this folder to a GitHub repo.
The included workflow builds the APK automatically → repo → Actions → latest run → download
`ClaudeHub-apk` → copy `app-debug.apk` to the phone and open it (allow "install unknown apps").

## 4. First run
1. Open **Claude Hub** → allow microphone + notifications.
2. Settings: paste your Anthropic key → Save. (First launch shows "Loading wake word…" for a few seconds.)
3. Tap **Disable battery optimization**.
4. Screen should say *Say "Claude"*. Try: "Claude… what's the weather in Miami tomorrow?"

Settings you can tweak: model (`claude-haiku-5-5` fastest, `claude-sonnet-5-5` smarter),
language (`en-US` / `pt-BR`), personality prompt, web search, follow-up listening.

## Memory & languages
- Say "Claude, remember that…" (or just mention lasting facts). Claude saves them on the phone and knows them
  in every future conversation. "What do you remember about me?" / "Forget number 3."
- Settings → Memory shows the list; edit or delete lines freely.
- Say "switch to Portuguese" / "fala em inglês" to change the listening language. With auto-switch on,
  the voice follows the language of each reply (set a male voice for both languages in Android's
  Text-to-speech settings).

## 5. Make it a 24/7 hub
- Settings → Battery and device care → Battery → More battery settings → **Protect battery** (caps at 85%) — essential.
- Remove the case, keep it ventilated, on a stand.
- Developer options → **Stay awake** (screen on while charging) if you want the display always visible.
- Settings → Apps → Claude Hub → Battery → **Unrestricted**. Also add it to *Never sleeping apps*
  (Battery → Background usage limits).
- After a reboot, open the app once.

## Troubleshooting
- **Doesn't hear "Claude" across the room** → speak a bit louder/clearer, or use a USB-C / Bluetooth speakerphone.
- **Triggers by itself** → it also accepts sound-alikes ("clawed", "clod"); edit WAKE_WORDS in AssistantService.kt.
- **"API error 401"** → wrong Anthropic key. **"API error 400 … credit"** → add credit.
- **Wake word failed** → if the build didn't bundle the model, the app downloads it on first launch (needs Wi-Fi); check your connection and restart the app.
- **Listening never starts** → make sure the *Google* app is installed/updated (it provides speech recognition).

## Files
- `VoskModel.kt` — unpacks the offline speech model
- `AssistantService.kt` — wake word → speech-to-text → Claude → text-to-speech loop
- `ClaudeClient.kt` — Claude Messages API call with tools (memory, language, web search)
- `Memory.kt` — long-term memory stored on the phone
- `MainActivity.kt` — the hub screen (tap anywhere to talk)
- `SettingsActivity.kt` — keys, wake word file, model, language, personality
