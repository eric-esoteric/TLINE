package com.tline.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

// Link to TRELAY, the per-app VPN that carries TLINE's SIP and RTP.
//
// When TRELAY moves a telephony route to a reserve server, the phone's own network does not change
// at all — the TUN interface and its address stay the same — so nothing in Android tells TLINE that
// its public address just became a different VPS. Without a signal the PBX would keep sending calls
// to the old server until the next REGISTER refresh. TRELAY therefore sends an explicit broadcast
// and TLINE re-registers at once, the same way it reacts to a real network change.
object Trelay {

    const val ACTION_FAILOVER = "com.tline.phone.TRELAY_FAILOVER"
    const val ACTION_STATE = "com.tline.phone.TRELAY_STATE"
    const val TRELAY_PACKAGE = "com.trelay.android"
    const val ACTION_QUERY = "com.trelay.android.SIP_STATE_QUERY"

    // TRELAY repeats its state every minute while the phone is awake; give it plenty of slack before
    // calling the picture stale, or a dozing phone makes TLINE cry wolf.
    private const val STALE_AFTER_MS = 6 * 60 * 1000L

    val route = mutableStateOf("")
    val server = mutableStateOf("")
    val detail = mutableStateOf("")
    val routeUp = mutableStateOf(false)
    val reportedAt = mutableStateOf(0L)
    val lastFailoverAt = mutableStateOf(0L)

    /** True while TRELAY is reporting; a silent TRELAY is either stopped or killed. */
    fun isFresh(): Boolean = reportedAt.value != 0L && System.currentTimeMillis() - reportedAt.value < STALE_AFTER_MS

    /** Just the route, for a line that already says everything else. */
    fun shortLine(): String = when {
        reportedAt.value == 0L || !isFresh() -> ""
        !routeUp.value -> "TRELAY: маршрут не работает"
        server.value.isEmpty() -> "TRELAY: ${route.value}"
        else -> "TRELAY: ${route.value} · «${server.value}»"
    }

    fun statusLine(): String = when {
        reportedAt.value == 0L -> "TRELAY не отвечает"
        !isFresh() -> "TRELAY: состояние неизвестно"
        !routeUp.value -> "TRELAY: маршрут не работает" + if (detail.value.isEmpty()) "" else " — ${detail.value}"
        server.value.isEmpty() -> "TRELAY: ${route.value}"
        else -> "TRELAY: ${route.value} · через «${server.value}»"
    }

    /** What to add to an alarm about a lost registration; never guesses that the VPN is off. */
    fun hint(): String = when {
        isFresh() && !routeUp.value -> "TRELAY сообщает, что маршрут не работает."
        isFresh() -> "Маршрут TRELAY работает, но доступ к АТС ещё не подтверждён. Проверьте адрес и SIP-порт в «Проверке сервиса через туннель»."
        reportedAt.value == 0L -> "TRELAY ничего не сообщал: проверьте, что VPN включён и TLINE в его маршруте."
        else -> "Состояние TRELAY давно не обновлялось."
    }

    fun remember(intent: Intent) {
        route.value = intent.getStringExtra("route") ?: route.value
        server.value = intent.getStringExtra("server") ?: ""
        detail.value = intent.getStringExtra("detail") ?: ""
        routeUp.value = intent.getBooleanExtra("up", true)
        reportedAt.value = System.currentTimeMillis()
    }

    /**
     * Re-registers every account through the new server. Closing and rebuilding the SIP transports is
     * what makes the PBX see the new source address immediately, which is the whole point of the
     * signal; a plain REGISTER over the dead transport would just time out.
     */
    fun reregister(context: Context, why: String) {
        lastFailoverAt.value = System.currentTimeMillis()
        if (!BaresipService.isServiceRunning || !BaresipService.isNativeReady) {
            Log.i(TAG, "Trelay: $why, but the service is not running - starting it")
            start(context)
            return
        }
        Log.i(TAG, "Trelay: $why - resetting SIP transports and registering again")
        try {
            Api.uag_reset_transp(register = true, reinvite = true)
        } catch (e: Exception) {
            Log.e(TAG, "Trelay: transport reset failed: $e")
        }
        // uag_reset_transp only re-registers accounts that have registration enabled; make sure every
        // such account is really on its way, and check once more when the new server is slow.
        Handler(Looper.getMainLooper()).postDelayed({ pushRegistrations() }, 1500)
        Handler(Looper.getMainLooper()).postDelayed({ pushRegistrations() }, 8000)
    }

    private fun pushRegistrations() {
        if (!BaresipService.isServiceRunning || !BaresipService.isNativeReady) return
        for (ua in BaresipService.uas.value) {
            val account = ua.account
            if (account.regint <= 0) continue
            try {
                if (!Api.ua_isregistered(ua.uap)) Api.ua_register(ua.uap)
            } catch (e: Exception) {
                Log.e(TAG, "Trelay: re-register of ${account.aor} failed: $e")
            }
        }
    }

    /** Asks TRELAY which route carries this app; without it the status line stays blank after a start. */
    fun requestState(context: Context) {
        try {
            context.sendBroadcast(
                Intent(ACTION_QUERY)
                    .setComponent(ComponentName(TRELAY_PACKAGE, "$TRELAY_PACKAGE.SipQueryReceiver"))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            )
        } catch (e: Exception) {
            Log.d(TAG, "Trelay: state query failed: $e")
        }
    }

    fun start(context: Context) {
        if (TelephonyPower.isPaused(context)) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BaresipService::class.java).apply { action = "Start" }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Trelay: could not start the service: $e")
        }
    }
}

/**
 * What TLINE itself is doing. baresip shows a registration failure as a two-second toast, which is
 * gone before the user reads it; the reason belongs on the screen, in words that say what to fix.
 */
object Line {

    val problem = mutableStateOf("")

    /** What TLINE is doing right now, in one word the user can act on. */
    enum class State { CONNECTED, NO_LINK, PROBLEM, OFF, NO_ACCOUNT, STOPPED }

    fun failed(reason: String) { problem.value = explain(reason) }

    fun ok() { problem.value = "" }

    fun state(): State {
        if (!BaresipService.isServiceRunning) return State.STOPPED
        val accounts = BaresipService.uas.value
        if (accounts.isEmpty()) return State.NO_ACCOUNT
        val registering = accounts.filter { it.account.regint > 0 }
        if (registering.isEmpty()) return State.OFF
        val registered = try {
            registering.any { Api.ua_isregistered(it.uap) }
        } catch (e: Exception) {
            false
        }
        if (!registered) return State.PROBLEM
        // Registered, but TRELAY says the route carrying this SIP traffic is down: the registration on
        // record at the PBX points at a server that cannot deliver a call any more.
        return if (Trelay.isFresh() && !Trelay.routeUp.value) State.NO_LINK else State.CONNECTED
    }

    /** The headline: the word the user reads first, on the screen and in the notification. */
    fun title(state: State = state()): String = when (state) {
        State.CONNECTED -> "Подключено"
        State.NO_LINK -> "Подключено без связи"
        State.PROBLEM -> "Проблема"
        State.OFF -> "Регистрация выключена"
        State.NO_ACCOUNT -> "Нет учётной записи"
        State.STOPPED -> "TLINE остановлен"
    }

    /** The line under it: what exactly, and what to do about it. */
    fun detail(state: State = state()): String {
        val account = BaresipService.uas.value.firstOrNull()?.account?.text() ?: ""
        return when (state) {
            State.CONNECTED -> listOf(account, Trelay.shortLine()).filter { it.isNotEmpty() }.joinToString(" · ")
            State.NO_LINK -> "Маршрут TRELAY не работает — входящие не дойдут" +
                    (if (Trelay.detail.value.isEmpty()) "" else ": ${Trelay.detail.value}")
            State.PROBLEM -> (if (problem.value.isEmpty()) "Нет подтверждённой SIP-регистрации" else problem.value) +
                    ". " + Trelay.hint()
            State.OFF -> "Включите «Register» в настройках учётной записи"
            State.NO_ACCOUNT -> "Добавьте учётную запись в «Аккаунтах»"
            State.STOPPED -> "Откройте TLINE, чтобы звонки снова доходили"
        }
    }

    // baresip reports whatever the stack said, in English: a SIP status line, or the name of an errno.
    // None of that tells the person holding the phone what to do, so every reason TLINE can recognise
    // is said in plain words, and an unknown one is at least labelled as coming from the PBX.
    private fun explain(reason: String): String {
        val text = reason.trim()
        return when {
            text.startsWith("401") || text.startsWith("407") || text.contains("Authentication", true) ||
                    text.contains("Unauthorized", true) ->
                "АТС не приняла логин или пароль"
            text.startsWith("403") || text.contains("Forbidden", true) ->
                "АТС отклонила учётную запись (проверьте, разрешён ли IP сервера)"
            text.startsWith("404") -> "АТС не знает такой учётной записи"
            text.startsWith("408") || text.contains("timeout", true) || text.contains("timed out", true) ->
                "Сервер SIP не ответил вовремя"
            text.startsWith("480") -> "АТС временно недоступна"
            text.startsWith("503") || text.startsWith("500") -> "АТС отвечает ошибкой сервера"
            text.contains("Connection reset", true) -> "связь с АТС разорвана по дороге"
            text.contains("Connection refused", true) -> "АТС не принимает соединение на этом порту"
            text.contains("Network is unreachable", true) || text.contains("No route to host", true) ->
                "нет сети до АТС"
            text.contains("Broken pipe", true) -> "соединение с АТС оборвалось"
            text.contains("Invalid argument", true) || text.contains("Name or service", true) ->
                "не удалось найти адрес АТС (DNS)"
            text.isEmpty() -> "Сервер SIP не ответил вовремя"
            else -> "АТС ответила: $text"
        }
    }
}

/** Receives TRELAY's signals. TRELAY addresses this receiver explicitly, by component. */
class TrelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Trelay.ACTION_FAILOVER -> {
                Trelay.remember(intent)
                val from = intent.getStringExtra("from") ?: "?"
                Trelay.reregister(context, "TRELAY switched from $from to ${Trelay.server.value}")
            }
            Trelay.ACTION_STATE -> {
                val wasUp = Trelay.routeUp.value && Trelay.isFresh()
                val previousServer = Trelay.server.value
                Trelay.remember(intent)
                // A route that just came back, or came back on another server, needs a fresh REGISTER too.
                if (Trelay.routeUp.value && (!wasUp || (previousServer.isNotEmpty() && previousServer != Trelay.server.value)))
                    Trelay.reregister(context, "TRELAY route is up again on ${Trelay.server.value}")
            }
        }
        // The route is part of what the status notification says, so it has to be redrawn here too.
        try { BaresipService.instance?.updateStatusNotification() } catch (e: Exception) { }
    }
}

/**
 * Keeps TLINE alive all day. Android does not reliably restart a killed foreground service, and an
 * inexact alarm is not allowed to start one from the background on Android 12+, so the watchdog uses
 * an exact alarm; when Android refuses anyway, the user hears it instead of losing calls silently.
 */
class TlineWatchdog : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return
        if (TelephonyPower.isPaused(context)) {
            cancel(context)
            return
        }
        try {
            if (!BaresipService.isServiceRunning) {
                Log.i(TAG, "Watchdog: service was not running - starting it")
                Trelay.start(context)
            } else {
                checkRegistrations(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog: restart refused: $e")
            Siren.alert(
                context,
                "TLINE остановлен",
                "Android выгрузил TLINE и не дал запустить его в фоне. Откройте TLINE, чтобы звонки снова доходили."
            )
        }
        schedule(context)
    }

    private fun checkRegistrations(context: Context) {
        if (!BaresipService.isNativeReady) return
        val accounts = BaresipService.uas.value.filter { it.account.regint > 0 }
        if (accounts.isEmpty()) return
        val lost = accounts.filter { !Api.ua_isregistered(it.uap) }
        if (lost.isEmpty()) {
            unregisteredSince = 0L
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (unregisteredSince == 0L) {
            unregisteredSince = now
            Log.i(TAG, "Watchdog: ${lost.size} account(s) not registered - registering again")
            Trelay.reregister(context, "watchdog found a lost registration")
            return
        }
        if (now - unregisteredSince < LOUD_AFTER_MS) return
        unregisteredSince = now
        // Ask TRELAY for a fresh picture before blaming it for anything.
        Trelay.requestState(context)
        Siren.alert(
            context,
            "TLINE: нет регистрации",
            "Учётная запись ${lost.first().account.aor} не зарегистрирована. " + Trelay.hint()
        )
    }

    companion object {
        const val ACTION_CHECK = "com.tline.phone.WATCHDOG_CHECK"
        private const val INTERVAL_MS = 5 * 60 * 1000L
        private const val LOUD_AFTER_MS = 2 * 60 * 1000L

        private var unregisteredSince = 0L

        fun schedule(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val at = SystemClock.elapsedRealtime() + INTERVAL_MS
            val pending = pending(context)
            val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
            try {
                if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
                else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            } catch (e: Exception) {
                try { alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending) } catch (_: Exception) { }
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
        }

        private fun pending(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 41,
                Intent(context, TlineWatchdog::class.java).setAction(ACTION_CHECK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
