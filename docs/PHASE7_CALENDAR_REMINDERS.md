# Phase 7 — Vietnamese lunar calendar, event editing and local reminders

## Added features

- Phone shows today's solar date, Vietnamese UTC+7 lunar date (including
  leap-month indicator), and Can Chi lunar year.
- Personal events now support editing type, repeat mode, title, date and
  lunar leap-month flag; deletion requires confirmation. The upcoming
  event list uses the existing `Events.nextDate` calculation.
- Existing `cfg/events` entries remain supported. Optional
  `leapMonth` defaults to `false` for older records. No change to the
  `CalEvent.id` or existing event keys.
- Invalid one-time calendar dates, nonexistent lunar leap months,
  oversized titles and repeat-mode combinations are rejected by the
  user-event validator. Leap month dates round-trip through
  `LunarCalendar.solarToLunar` to reject impossible dates.

## Local reminders (opt-in)

`EventReminderPolicy` computes occurrences without Android APIs.
`EventReminderScheduler` requests an **inexact daily wake-up around
09:00 in the device's current timezone** using `AlarmManager.setAndAllowWhileIdle`.
Actual delivery may be delayed or suppressed by battery saver or OEM policy.

No notifications are enabled by default. The user can select reminders
on the day and optionally 1, 3, or 7 days before. Additional default
holidays are opt-in, excluding the monthly first/full-moon recurring
defaults. The Android 13+ `POST_NOTIFICATIONS` runtime permission is
requested only upon explicit enabling; the scheduler cancels when
disabled or permission is missing.

The broadcast receiver reschedules on the daily tick, reboot, app
replacement, timezone change, or manual clock change. The Activity
also reschedules when opened. Per-day notification deduplication uses
private local preferences, avoids repeat notifications for the same
event occurrence, and caps a daily batch at six. There is no server,
background GPS access, exact-alarm permission, or new Android Auto action.

The lunar conversion is computed using the existing UTC+7 Vietnamese
calendar; the reminder's 09:00 delivery time uses the device timezone.
Users outside Vietnam should not interpret this as a different
locale-specific lunar calendar.

## Limitations and validation

Scheduled notifications are best-effort and may not appear if the user
disables app notifications, force-stops the app, or device policy
restricts alarms. The app does not issue audible driving alerts through
Android Auto.

The JUnit suite tests existing event compatibility, lunar leap month,
invalid dates, annual/monthly/one-time recurrences, opt-in gating,
lead-time selection, default holidays and local morning scheduling.
CI builds the debug APK and runs existing tests. Real-device notification
permission flows and alarm delivery still require Android verification.

The previously deferred CPU/RAM/latency measurements of GPS matching
and road graph are **not part of Phase 7**.
