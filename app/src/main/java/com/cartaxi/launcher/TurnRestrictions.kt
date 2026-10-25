package com.cartaxi.launcher

/** OSM `type=restriction` via-node subset, not a legal routing engine. */
internal data class OsmTurnRestriction(
    val relationId: Long,
    val fromWayId: Long,
    val toWayId: Long?,
    val viaNodeId: Long?,
    val restriction: String?,
    /** Exceptions/conditional/special member layout are not evaluated. */
    val unsupported: Boolean = false
)

internal enum class TurnDecision {
    NO_KNOWN_RESTRICTION,
    PROHIBITED,
    UNKNOWN
}

/**
 * Index by from-way ID. `no_*` denies its specified to-way; `only_*`
 * denies other to-ways through that junction. Unknown rules fail closed for
 * fast GPS turn confirmation and graph traversal.
 */
internal class OsmTurnRestrictions(records: List<OsmTurnRestriction> = emptyList()) {
    private val fromWay = records.filter { it.fromWayId > 0L }
        .groupBy { it.fromWayId }

    fun decision(fromId: Long, viaNodeId: Long, toId: Long): TurnDecision {
        val relevant = fromWay[fromId].orEmpty().filter { record ->
            record.viaNodeId == viaNodeId || record.viaNodeId == null
        }
        if (relevant.isEmpty()) return TurnDecision.NO_KNOWN_RESTRICTION
        var blocked = false
        for (r in relevant) {
            if (r.unsupported || r.viaNodeId == null || r.toWayId == null ||
                r.restriction.isNullOrBlank()
            ) return TurnDecision.UNKNOWN
            val type = r.restriction.lowercase()
            when {
                type in setOf(
                    "no_left_turn", "no_right_turn", "no_straight_on",
                    "no_u_turn", "no_entry", "no_exit"
                ) -> if (r.toWayId == toId) blocked = true
                type in setOf(
                    "only_left_turn", "only_right_turn",
                    "only_straight_on", "only_u_turn"
                ) -> if (r.toWayId != toId) blocked = true
                else -> return TurnDecision.UNKNOWN
            }
        }
        return if (blocked) TurnDecision.PROHIBITED
            else TurnDecision.NO_KNOWN_RESTRICTION
    }
}
