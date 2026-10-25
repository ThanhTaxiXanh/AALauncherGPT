# CI GPS Replay Regression

## Versioned GPS replay fixture

`app/src/test/resources/gps_replay/turn_tracks.csv` stores deterministic
accepted GPS samples (timestamp, east/north metres, heading, accuracy,
moving state, expected matched/confirmed way, alert count). It covers
five separate tracks: a permitted observed turn, an OSM-prohibited
turn needing delayed *position reacquisition*, a one-fix way-matching
jitter, poor reported GPS accuracy and two nearby geometries without
a shared OSM node. The JUnit replay invokes `SpeedWayMatcher`,
`ObservedRoadTurnTracker`, `OsmRoadGraph`, and the public warning
lookup together; it is automatically included in GitHub Actions
`:app:testDebugUnitTest`.

These are synthetic GPS traces and are not equivalent to logged GPS
from a car on public roads.
