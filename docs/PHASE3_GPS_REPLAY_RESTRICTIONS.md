# Phase 3 — GPS Replay & OSM turn restrictions (increment 1)

## OSM restriction data

Overpass requests now include `relation(around:900,...)[type=restriction]`
alongside ways and sign nodes. The parser reads roles `from` (way),
`via` (node), `to` (way) and `restriction`, preferring
`restriction:motorcar` if present. The pure Kotlin `OsmTurnRestrictions`
index recognizes the common via-node `no_left_turn`, `no_right_turn`,
`no_straight_on`, `no_u_turn`, `no_entry`, `no_exit`,
`only_left_turn`, `only_right_turn`, `only_straight_on`,
and `only_u_turn` rules.

Unsupported `via`-way relations, conditional rules, exceptions and
unrecognized vehicle restrictions yield UNKNOWN when identifiable from
the arriving way; they are *not* silently interpreted as permissions.
A missing Overpass relation is not proof that a turn is legal.

`OsmRoadGraph.trace` stops before a prohibited or uncertain turn.
`observedTransition` does not immediately confirm a prohibited
transition. The existing `ObservedRoadTurnTracker` may still reacquire
a road after four independent GPS observations: that is a **position
estimate**, not authorization to perform a prohibited manoeuvre.

## Tests

`OsmTurnRestrictionsTest` tests pure Kotlin decisions and graph
integration without a network dependency. GPS replay fixtures are
executed as JUnit tests in `:app:testDebugUnitTest` on each CI push.

## Boundaries

This does not implement full vehicle-specific access, time-dependent
restrictions or an authoritative navigation router. It is advisory
OSM data which may be incomplete or outdated. Real vehicle GPS playback
and Android Auto hardware tests remain necessary.
