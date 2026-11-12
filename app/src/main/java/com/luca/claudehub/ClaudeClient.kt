package com.luca.claudehub

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Minimal client for the Claude Messages API with tool use. Call from a background thread. */
object ClaudeClient {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MAX_STEPS = 6

    /** Runs a tool the app provides (remember, forget, set_language) and returns its result text. */
    fun interface ToolHandler {
        fun run(name: String, input: JSONObject): String
    }

    /** Tools Claude can call on the phone. */
    private val APP_TOOLS: List<JSONObject> = listOf(
        tool(
            "remember",
            "Save one lasting fact about Luca (a preference, routine, person, plan or detail) so you know it in all future conversations. " +
                "Use it when he asks you to remember something, or when he shares something clearly worth keeping. One short fact per call.",
            JSONObject().put("fact", JSONObject().put("type", "string").put("description", "The fact, written in third person, e.g. 'Goes to the gym at 7am Mon/Wed/Fri'")),
            "fact"
        ),
        tool(
            "forget",
            "Delete a remembered fact by its number in the memory list, when Luca asks you to forget it or it is outdated. " +
                "To update a fact, forget the old one and remember the new one.",
            JSONObject().put("number", JSONObject().put("type", "integer")),
            "number"
        ),
        tool(
            "set_language",
            "Switch the language the hub listens in and speaks. Call it when Luca asks to switch, or when he starts talking to you in the other language.",
            JSONObject().put("language", JSONObject().put("type", "string").put("enum", JSONArray().put("en-US").put("pt-BR"))),
            "language"
        )
    )

    private fun tool(name: String, description: String, props: JSONObject, required: String) =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("input_schema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray().put(required)))

    fun ask(
        apiKey: String, model: String, system: String, history: List<JSONObject>,
        webSearch: Boolean, tools: ToolHandler
    ): String {
        val messages = JSONArray()
        history.forEach { messages.put(it) }
        var useWeb = webSearch

        repeat(MAX_STEPS) {
            var (code, body) = post(apiKey, buildBody(model, system, messages, useWeb))
            // If web search isn't available on this account, retry without it.
            if (code == 400 && useWeb) {
                useWeb = false
                val retry = post(apiKey, buildBody(model, system, messages, false))
                code = retry.first; body = retry.second
            }
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(200))
                throw IOException("API error $code: $msg")
            }

            val resp = JSONObject(body)
            val content = resp.optJSONArray("content") ?: JSONArray()
            when (resp.optString("stop_reason")) {
                "tool_use" -> {
                    messages.put(JSONObject().put("role", "assistant").put("content", content))
                    val results = JSONArray()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") != "tool_use") continue
                        val output = runCatching {
                            tools.run(block.getString("name"), block.optJSONObject("input") ?: JSONObject())
                        }.getOrElse { "Error: ${it.message}" }
                        results.put(
                            JSONObject().put("type", "tool_result")
                                .put("tool_use_id", block.getString("id"))
                                .put("content", output)
                        )
                    }
                    messages.put(JSONObject().put("role", "user").put("content", results))
                }
                "pause_turn" -> {
                    // long web search: send it back so Claude can continue
                    messages.put(JSONObject().put("role", "assistant").put("content", content))
                }
                else -> return cleanForSpeech(extractText(content))
            }
        }
        return "Sorry, that took too many steps."
    }

    private fun buildBody(model: String, system: String, messages: JSONArray, webSearch: Boolean): JSONObject {
        val tools = JSONArray()
        APP_TOOLS.forEach { tools.put(it) }
        if (webSearch) {
            tools.put(
                JSONObject()
                    .put("type", "web_search_20250305")
                    .put("name", "web_search")
                    .put("max_uses", 3)
            )
        }
        return JSONObject()
            .put("model", model)
            .put("max_tokens", 800)
            .put("system", system)
            .put("messages", messages)
            .put("tools", tools)
    }

    private fun post(apiKey: String, body: JSONObject): Pair<Int, String> {
        val conn = URL(ENDPOINT).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** Keep only the final answer (skip "let me search..." text before tool results). */
    private fun extractText(content: JSONArray): String {
        var lastToolResult = -1
        for (i in 0 until content.length()) {
            if (content.getJSONObject(i).optString("type").endsWith("tool_result")) lastToolResult = i
        }
        fun join(from: Int): String {
            val sb = StringBuilder()
            for (i in from until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            return sb.toString().trim()
        }
        return join(lastToolResult + 1).ifEmpty { join(0) }
    }

    /** Strip leftover markdown so TTS doesn't read symbols aloud. */
    private fun cleanForSpeech(s: String): String =
        s.replace(Regex("\\[(.*?)]\\((.*?)\\)"), "$1")
            .replace(Regex("[*#`_>]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
