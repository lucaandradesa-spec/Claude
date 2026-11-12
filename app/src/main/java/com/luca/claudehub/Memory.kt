package com.luca.claudehub

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Long-term memory: a numbered list of facts about Luca, stored privately on the phone. */
object Memory {
    private const val MAX_FACTS = 150

    private fun file(c: Context) = File(c.filesDir, "memory.json")

    @Synchronized
    fun all(c: Context): MutableList<String> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { arr.getString(it) }
        }.getOrDefault(mutableListOf())
    }

    @Synchronized
    fun setAll(c: Context, facts: List<String>) {
        val arr = JSONArray()
        facts.map { it.trim() }.filter { it.isNotEmpty() }.takeLast(MAX_FACTS).forEach { arr.put(it) }
        file(c).writeText(arr.toString())
    }

    @Synchronized
    fun add(c: Context, fact: String): String {
        val clean = fact.trim()
        if (clean.isEmpty()) return "Nothing to save."
        val list = all(c)
        if (list.any { it.equals(clean, ignoreCase = true) }) return "Already remembered."
        list.add(clean)
        setAll(c, list)
        return "Saved."
    }

    @Synchronized
    fun remove(c: Context, number: Int): String {
        val list = all(c)
        if (number !in 1..list.size) return "No fact #$number."
        val removed = list.removeAt(number - 1)
        setAll(c, list)
        return "Forgot: $removed"
    }

    /** Section added to the system prompt on every request. */
    fun promptSection(c: Context): String {
        val facts = all(c)
        val list = if (facts.isEmpty()) "(nothing saved yet)"
        else facts.mapIndexed { i, f -> "${i + 1}. $f" }.joinToString("\n")
        return "\n\nLong-term memory — facts you have saved about Luca (use them naturally; don't recite them unless asked):\n" +
            list + "\n\n" +
            "You have a remember tool: when Luca tells you something lasting about himself, his routines, preferences, " +
            "people or plans, or asks you to remember something, save it without making a fuss (a short \"got it\" is enough). " +
            "Use forget with the number when he asks you to forget something or a fact changed. " +
            "Don't save small talk or things only relevant right now."
    }
}
