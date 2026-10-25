package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.road.OsmRoadGraph
import Thanh.TaxiDriver.AALauncher.road.OsmTurnRestriction
import Thanh.TaxiDriver.AALauncher.road.OsmTurnRestrictions
import Thanh.TaxiDriver.AALauncher.road.SpeedWayGeometry
import Thanh.TaxiDriver.AALauncher.road.SpeedWayMatch
import Thanh.TaxiDriver.AALauncher.road.TurnDecision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmTurnRestrictionsTest {
    private fun r(type: String?, to: Long? = 20L, via: Long? = 2L, unsupported: Boolean = false) =
        OsmTurnRestriction(100L, 10L, to, via, type, unsupported)

    @Test fun noLeftTurnOnlyDeniesSpecifiedDestinationAtViaNode() {
        val index = OsmTurnRestrictions(listOf(r("no_left_turn")))
        assertEquals(TurnDecision.PROHIBITED, index.decision(10, 2, 20))
        assertEquals(TurnDecision.NO_KNOWN_RESTRICTION, index.decision(10, 2, 30))
        assertEquals(TurnDecision.NO_KNOWN_RESTRICTION, index.decision(10, 3, 20))
        assertEquals(TurnDecision.NO_KNOWN_RESTRICTION, index.decision(11, 2, 20))
    }

    @Test fun onlyTurnProhibitsAlternatives() {
        val index = OsmTurnRestrictions(listOf(r("only_right_turn")))
        assertEquals(TurnDecision.NO_KNOWN_RESTRICTION, index.decision(10, 2, 20))
        assertEquals(TurnDecision.PROHIBITED, index.decision(10, 2, 30))
    }

    @Test fun unknownConditionalExceptionsAndViaWayFailClosed() {
        for (rule in listOf(
            r("no_left_turn", unsupported = true), r("no_left_turn", via = null),
            r("only_right_turn", to = null), r("give_way"),
            r(null), r("no_left_turn", unsupported = true)
        )) assertEquals(TurnDecision.UNKNOWN,
            OsmTurnRestrictions(listOf(rule)).decision(10, 2, 20))
    }

    @Test fun unknownRuleOverridesApparentPermission() {
        val index = OsmTurnRestrictions(listOf(r("no_left_turn", to = 30),
            r("no_right_turn", unsupported = true)))
        assertEquals(TurnDecision.UNKNOWN, index.decision(10, 2, 20))
    }

    @Test fun graphStopsBeforeProhibitedOnlyTurnAndTreatsTransitionAsUnconfirmed() {
        val m = 111_320.0
        val a = SpeedWayGeometry(10, 0,
            doubleArrayOf(0.0, -100/m, 0.0, 0.0),
            longArrayOf(1,2))
        val b = SpeedWayGeometry(20, 0,
            doubleArrayOf(0.0, 0.0, 0.0, 100/m),
            longArrayOf(2,3))
        val index = OsmTurnRestrictions(listOf(r("no_left_turn")))
        val graph = OsmRoadGraph(listOf(a,b), index)
        assertEquals(1, graph.trace(0,0,true,0.5).size)
        assertFalse(graph.observedTransition(
            SpeedWayMatch(0,10,true), SpeedWayMatch(1,20,true),
            0.0, -30/m, 0.0, 20/m
        ))
        val allowedGraph = OsmRoadGraph(listOf(a,b))
        assertTrue(allowedGraph.observedTransition(
            SpeedWayMatch(0,10,true), SpeedWayMatch(1,20,true),
            0.0, -30/m, 0.0, 20/m
        ))
    }
}
