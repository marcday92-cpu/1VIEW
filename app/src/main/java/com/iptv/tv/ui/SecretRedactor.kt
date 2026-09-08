package com.iptv.tv.ui

/**
 * Strips IPTV credentials out of text before it reaches logcat or the crash file.
 * Xtream URLs carry them twice: as query parameters and as path segments.
 */
object SecretRedactor {
    private val QUERY = Regex("(?i)(username|password|api_key|apikey|token)=([^&\\s\"']+)")
    private val PATH = Regex("(?i)/(live|movie|series|timeshift)/[^/\\s\"']+/[^/\\s\"']+/")

    fun redact(text: String): String = text
        .replace(QUERY, "$1=[redacted]")
        .replace(PATH, "/$1/[redacted]/[redacted]/")

    /** Class name plus redacted message, walking the cause chain. */
    fun describe(error: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            parts += current.javaClass.simpleName + (current.message?.let { ": " + redact(it) } ?: "")
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return parts.joinToString(" <- ")
    }
}
