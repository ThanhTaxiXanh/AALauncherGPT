package com.cartaxi.launcher

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.LocalDate
import java.time.ZonedDateTime

/** No network access, location access, exact alarms or foreground service. */
object EventReminderScheduler {
    private const val PREFS = "event_reminders"
    private const val CHANNEL = "personal_calendar"
    private const val ACTION_TICK = "com.cartaxi.launcher.EVENT_REMINDER_TICK"
    private const val REQUEST_CODE = 727
    private const val HOUR = 9

    fun read(context: Context): EventReminderSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return EventReminderSettings(
            enabled = prefs.getBoolean("enabled", false),
            advanceDays = prefs.getInt("advanceDays", 1).takeIf { it in setOf(0,1,3,7) } ?: 1,
            includeHolidays = prefs.getBoolean("includeHolidays", false)
        )
    }

    fun save(context: Context, settings: EventReminderSettings) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putInt("advanceDays", settings.advanceDays)
            .putBoolean("includeHolidays", settings.includeHolidays)
            .commit()) { "Không lưu được cài đặt nhắc lịch." }
        refresh(context)
    }

    fun permissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, REQUEST_CODE,
            Intent(context, EventReminderReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun refresh(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pending(context)
        // Off by default; missing permission never triggers a notification.
        if (!read(context).enabled || !permissionGranted(context)) {
            alarms.cancel(intent)
            return
        }
        val at = EventReminderPolicy.nextMorningMillis(ZonedDateTime.now(), HOUR)
        // On Android 12+ this is inexact and may be delayed by Doze/OEM policy.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    internal fun deliver(context: Context) {
        val config = read(context)
        if (!config.enabled || !permissionGranted(context)) return
        val today = LocalDate.now()
        val due = EventReminderPolicy.due(Events.load(context), today, config)
        if (due.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Nhắc lịch cá nhân", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val todayTag = today.toString()
        val sent = if (prefs.getString("deliveredDay", "") == todayTag)
            prefs.getStringSet("deliveredIds", emptySet())?.toMutableSet() ?: mutableSetOf()
        else mutableSetOf()
        val openApp = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        for (item in due) {
            if (!EventReminderPolicy.shouldDeliver(today, item.event, item.date, sent))
                continue
            val sub = if (item.daysRemaining == 0) "Hôm nay" else
                "Còn ${item.daysRemaining} ngày · ${item.date.dayOfMonth}/${item.date.monthValue}"
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Lịch: ${item.event.title.take(100)}")
                .setContentText(sub)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build()
            try {
                manager.notify(item.event.id.hashCode() xor item.date.toEpochDay().toInt(), notification)
                sent.add(EventReminderPolicy.key(item.event, item.date))
            } catch (e: SecurityException) {
                logErr("EventReminder:notify", e)
                break
            }
        }
        prefs.edit().putString("deliveredDay", todayTag)
            .putStringSet("deliveredIds", sent).commit()
    }
}

class EventReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "com.cartaxi.launcher.EVENT_REMINDER_TICK") {
            try {
                EventReminderScheduler.deliver(context)
            } catch (e: Exception) {
                logErr("EventReminder:deliver", e)
            }
        }
        // Boot, package update, timezone changes and the daily tick all
        // re-arm the next local morning using the current system timezone.
        try {
            EventReminderScheduler.refresh(context)
        } catch (e: Exception) {
            logErr("EventReminder:refresh", e)
        }
    }
}
