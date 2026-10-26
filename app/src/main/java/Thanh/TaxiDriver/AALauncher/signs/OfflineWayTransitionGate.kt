package Thanh.TaxiDriver.AALauncher.signs

/**
 * V3.3 offline-only transition confirmation. Do not apply to the shared
 * SpeedWayMatcher: online turn tracking and GPS replay depend on immediate
 * geometry matches for topology/restriction checks.
 */
internal class OfflineWayTransitionGate(private val requiredFixes: Int = 3) {
    private var accepted: Long? = null
    private var pending: Long? = null
    private var consecutive = 0

    fun reset() {
        accepted = null
        pending = null
        consecutive = 0
    }

    fun accept(wayId: Long): Boolean {
        if (wayId <= 0L) { reset(); return false }
        if (accepted == null || accepted == wayId) {
            accepted = wayId
            pending = null
            consecutive = 0
            return true
        }
        if (pending == wayId) consecutive++ else {
            pending = wayId
            consecutive = 1
        }
        if (consecutive < requiredFixes) return false
        accepted = wayId
        pending = null
        consecutive = 0
        return true
    }
}
