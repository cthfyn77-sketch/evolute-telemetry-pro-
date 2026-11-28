package com.evolute.telemetrypro

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Safe on-device diagnostics. Tokens, IMEI and raw API payloads are never stored. */
object CollectionDiagnostics {
    private const val PREFS = "collection_diagnostics"

    fun record(context: Context, message: String, success: Boolean? = null) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val safe = message.replace(Regex("[\\r\\n\\t]+"), " ").take(220)
        val editor = prefs.edit()
            .putString("last_message", safe)
            .putLong("last_time", System.currentTimeMillis())
        if (success == true) editor.putLong("success_count", prefs.getLong("success_count", 0L) + 1L)
        if (success == false) editor.putLong("failure_count", prefs.getLong("failure_count", 0L) + 1L)
        editor.apply()
    }

    fun summary(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = prefs.getLong("last_time", 0L)
        val stamp = if (time == 0L) "ещё не запускался" else
            SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(time))
        val message = prefs.getString("last_message", "Нет записей диагностики") ?: "Нет записей диагностики"
        return "Сбор: $message • $stamp\nУспешных снимков: ${prefs.getLong("success_count", 0L)} • ошибок: ${prefs.getLong("failure_count", 0L)}"
    }
}
