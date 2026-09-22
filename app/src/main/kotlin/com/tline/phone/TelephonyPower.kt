package com.tline.phone

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** User controlled, persistent pause of every TLINE telephony background task. */
object TelephonyPower {
    private const val PREFS = "telephony_power"
    private const val PAUSED = "paused"

    val paused = MutableStateFlow(false)

    fun load(context: Context) {
        paused.value = isPaused(context)
    }

    fun isPaused(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PAUSED, false)

    fun setPaused(context: Context, value: Boolean) {
        // commit is deliberate: a boot or watchdog must not race with a queued disk write.
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAUSED, value).commit())
            throw IllegalStateException("Could not save telephony power state")
        paused.value = value
    }
}
