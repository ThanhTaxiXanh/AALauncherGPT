package Thanh.TaxiDriver.AALauncher.signs



import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Conservative subset of OSM maxspeed:conditional.
 *
 * Accepted: "50 @ (Mo-Fr 07:00-09:00)" or multiple non-conflicting
 * semicolon-separated day/time clauses; all-day weekdays and public holidays,
 * weather, school rules, dates, sunrise/sunset and overnight spans are NOT
 * evaluated. Unsupported/overlapping ambiguous clauses fail closed (null).
 *
 * OSM conditions use local civil time. This app currently targets Vietnam and
 * evaluates in Asia/Ho_Chi_Minh regardless of the device's time zone.
 */
internal object ConditionalSpeedLimit {
    private val vietNam = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private val speedPattern = Regex("""^(\d{1,3})(?:\s*(mph|km/h|kmh|kph))?$""", RegexOption.IGNORE_CASE)
    private val rulePattern = Regex("""^\s*(\d{1,3}(?:\s*(?:mph|km/h|kmh|kph))?)\s*@\s*\(([^()]*)\)\s*$""", RegexOption.IGNORE_CASE)
    private val conditionPattern = Regex("""^((?:[A-Za-z]{2}(?:-[A-Za-z]{2})?)(?:,[A-Za-z]{2}(?:-[A-Za-z]{2})?)*)?\s*(\d{2}:\d{2})-(\d{2}:\d{2})$""")
    private val dayNumbers = mapOf("Mo" to 1, "Tu" to 2, "We" to 3, "Th" to 4, "Fr" to 5, "Sa" to 6, "Su" to 7)

    private data class TimeRule(
        val speed: Int,
        val days: Set<Int>?,
        val startMinutes: Int,
        val endMinutes: Int
    ) {
        fun applies(day: Int, minute: Int) =
            (days == null || day in days) && minute >= startMinutes && minute < endMinutes
    }

    fun parseSpeed(raw: String?): Int? {
        val input = raw?.trim() ?: return null
        if (input.equals("walk", ignoreCase = true)) return 7 // preserve existing OSM handling
        val match = speedPattern.matchEntire(input) ?: return null
        var speed = match.groupValues[1].toInt()
        if (match.groupValues[2].equals("mph", ignoreCase = true)) speed = (speed * 1.609).roundToInt()
        return speed.takeIf { it in 5..160 }
    }

    /**
     * No conditional tag -> existing explicit base speed.
     * Non-empty unsupported tag -> unknown (null), never an invented legal limit.
     */
    fun resolve(baseSpeed: Int?, conditional: String?, timestampMs: Long): Int? {
        if (conditional == null) return baseSpeed
        val clauses = conditional.split(';')
        if (clauses.isEmpty() || clauses.size > 8 || clauses.any { it.isBlank() }) return null
        val rules = clauses.map { parseRule(it) ?: return null }
        val date = Calendar.getInstance(vietNam).apply { timeInMillis = timestampMs }
        val day = ((date.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val minute = date.get(Calendar.HOUR_OF_DAY) * 60 + date.get(Calendar.MINUTE)
        val matching = rules.filter { it.applies(day, minute) }
        if (matching.isEmpty()) return baseSpeed
        // Conflicting active rules may be legally complex; decline to guess.
        val speed = matching.first().speed
        return if (matching.all { it.speed == speed }) speed else null
    }

    private fun parseRule(raw: String): TimeRule? {
        val match = rulePattern.matchEntire(raw) ?: return null
        val speed = parseSpeed(match.groupValues[1]) ?: return null
        val condition = conditionPattern.matchEntire(match.groupValues[2].trim()) ?: return null
        val days = parseDays(condition.groupValues[1]) ?: if (condition.groupValues[1].isEmpty()) null else return null
        val start = parseMinute(condition.groupValues[2]) ?: return null
        val end = parseMinute(condition.groupValues[3]) ?: return null
        // Overnight ranges need cross-day resolution. Do not silently assume.
        if (start >= end || start == 1440) return null
        return TimeRule(speed, days, start, end)
    }

    private fun parseDays(raw: String): Set<Int>? {
        if (raw.isBlank()) return null
        val result = mutableSetOf<Int>()
        for (item in raw.split(',')) {
            val range = item.split('-')
            if (range.size !in 1..2) return null
            val begin = dayNumbers[range[0]] ?: return null
            val end = if (range.size == 2) dayNumbers[range[1]] ?: return null else begin
            // Wrapped week ranges may be ambiguous to casual readers; skip them.
            if (begin > end) return null
            for (d in begin..end) result.add(d)
        }
        return result
    }

    private fun parseMinute(raw: String): Int? {
        val parts = raw.split(':')
        if (parts.size != 2) return null
        val hours = parts[0].toIntOrNull() ?: return null
        val minutes = parts[1].toIntOrNull() ?: return null
        if (hours !in 0..24 || minutes !in 0..59 || (hours == 24 && minutes != 0)) return null
        return hours * 60 + minutes
    }
}
