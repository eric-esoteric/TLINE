package com.tline.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The TRELAY alarm, in TLINE. A lost registration is as bad as a lost tunnel, so it sounds the same:
 * a loud two-tone pattern generated in memory, played on the alarm stream and, by default, on the
 * phone's own speaker even when a headset is connected.
 */
object Siren {

    const val ALERT_CHANNEL_ID = "com.tline.phone.alert"
    private const val SAMPLE_RATE = 22050
    private const val ALERT_NOTIFICATION_ID = 121

    private var alarm: ShortArray? = null
    private var lastAlarm = 0L

    fun alert(context: Context, title: String, message: String) {
        val app = context.applicationContext
        notify(app, title, message)
        play(app)
    }

    @Synchronized
    fun play(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAlarm < 2000) return // never stack alarms that arrive together
        lastAlarm = now
        val data = alarm ?: buildAlarm().also { alarm = it }
        vibrate(context)
        Thread({ playBlocking(context, data) }, "tline-alert").start()
    }

    private fun notify(context: Context, title: String, message: String) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID, "Обрыв связи", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Потеря регистрации и остановка TLINE"
                setSound(null, null) // TLINE plays its own alarm
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val content = PendingIntent.getActivity(
                context, 31, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = Notification.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_b)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .build()
            nm.notify(ALERT_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Siren: notification failed: $e")
        }
    }

    private fun playBlocking(context: Context, data: ShortArray) {
        var track: AudioTrack? = null
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            track.write(data, 0, data.size)
            speaker(context)?.let { track.setPreferredDevice(it) }
            track.play()
            Thread.sleep(data.size * 1000L / SAMPLE_RATE + 150)
        } catch (e: Exception) {
            Log.e(TAG, "Siren: playback failed: $e")
        } finally {
            try { track?.stop() } catch (_: Exception) { }
            track?.release()
        }
    }

    private fun speaker(context: Context): AudioDeviceInfo? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    private fun vibrate(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31)
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else
                context.getSystemService(Vibrator::class.java)
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400, 150, 600), -1))
        } catch (_: Exception) { }
    }

    private fun buildAlarm(): ShortArray {
        val samples = ArrayList<Short>(SAMPLE_RATE)
        repeat(3) {
            tone(samples, 988.0, 150, 0.85)
            silence(samples, 40)
            tone(samples, 1319.0, 150, 0.85)
            silence(samples, 170)
        }
        return samples.toShortArray()
    }

    private fun tone(output: MutableList<Short>, frequency: Double, milliseconds: Int, volume: Double) {
        val count = SAMPLE_RATE * milliseconds / 1000
        val edge = SAMPLE_RATE * 8 / 1000
        for (i in 0 until count) {
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = min(1.0, min(i.toDouble() / edge, (count - i).toDouble() / edge))
            val value = (sin(2 * PI * frequency * t) + 0.3 * sin(2 * PI * frequency * 3 * t)) / 1.3
            output.add((value * envelope * volume * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }

    private fun silence(output: MutableList<Short>, milliseconds: Int) {
        repeat(SAMPLE_RATE * milliseconds / 1000) { output.add(0) }
    }
}
