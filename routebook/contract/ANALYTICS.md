# Private Routebook analytics v1

Additive private-listener API; ingest, Android collection and canonical v1 map derivation
are unchanged. TypeScript shapes are normative in `analytics.d.ts`. Response errors remain
`{code:string}`. No fuel/cost, map matching, geocoding or additional telemetry.

- `GET /v1/analytics?device_id=UUID&from=YYYY-MM-DD&to=YYYY-MM-DD` returns AnalyticsResponse.
- `GET /v1/analytics/journey?device_id=UUID&from=YYYY-MM-DD&to=YYYY-MM-DD&journey_id=UUID`
  returns JourneyDetailResponse using the same range/context, full stored points and
  canonical disconnected geometry runs. Missing/replaced journey returns 404 journey_not_found.
- `GET /v1/places?device_id=UUID` returns PlacesResponse.
- `POST /v1/places?device_id=UUID`, JSON PlaceWrite, creates a stable random place UUID.
- `PUT /v1/places/UUID?device_id=UUID`, JSON PlaceWrite, replaces its metadata.
- `DELETE /v1/places/UUID?device_id=UUID`, JSON `{expected_metadata_revision:number}` deletes
  metadata only. All mutations return the complete PlacesResponse, use optimistic revision
  checks (409 metadata_conflict), strict keys and JSON; names 1..80 trimmed characters,
  finite WGS84 coordinates, radius 25..1000 metres (default 100). Maximum 200 places/device.
  Write requests require `X-Routebook-Write: 1` and reject cross-site Fetch Metadata or an
  Origin whose host differs from the actual request Host. No browser bearer token.

Dates are inclusive Warsaw calendar dates, maximum 31. Analytics loads a bounded 24-hour
context on either side and at most 650000 points (selected detail maximum 100000 points).
Exceeding a safe limit returns 422
analytics_limit; it never silently truncates the dataset. Context boundaries do not certify
journey completeness. A journey overlapping the selected range appears once, with full metrics
including context. Totals/daily/weekly/monthly summaries clip time to the requested date range;
existing elapsed/moving/stopped/unknown time summarizes journey intervals only, excluding
>=600-second boundary parking. Recorded time below includes observed parking independently.
Visits expose that observed parking duration separately; never add it to journey time.
distance retains canonical v1 attribution to the ending point's local date. Zero days are
included; ISO weeks begin Monday and months/weeks may represent partial selected periods.

Journeys split at confirmed observed stops >=600 seconds (parking excluded from both
neighbouring journeys) and observation gaps >=600 seconds. A source segment change alone
does not split a journey. Short gaps and source segment breaks are unknown elapsed time,
never a geometry bridge. Starts/ends at archive/context boundaries or a gap are incomplete;
both confirmed parking boundaries and no unknown interior intervals are required for complete.
Missing observations before the first or after the last point are never parking.

Canonical v1 distance/stop filtering is reused exactly, including observed stops >=180s.
Moving intervals use their own accepted distance/time; moving_average_mps uses exactly
that moving_meters numerator and moving_seconds denominator. Low-speed reported evidence
or confirmed observed stop intervals are stopped; unresolved zero-distance intervals are
unknown. Null speed remains null in raw samples. overall_average_mps is null for any
incomplete recording. observed_max_mps is the maximum reported speed supported by a
connected, quality-qualified moving edge (accuracy <=25m, plausible geometry speed and
reported speed within max(5m/s, 50%) of geometry speed). It is an observed sample, never a
speed-limit or legal measurement. A lone spike and null samples cannot become a maximum.

Visits are canonical observed stops, with actual observed interval, selected-range overlap
and nullable named place. Place matching uses centers within each place radius: nearest
haversine distance wins, then lexicographically smallest place UUID on an exact tie.
Journey endpoints use the same rule. A→B comparisons only group journeys with both named
endpoints, preserve direction, include complete_count and Warsaw departure-hour breakdown.
The same endpoints do not imply the same roads. Means describe observed journey elapsed
time/distance/stops; incompleteness stays visible through complete_count.

source_revision is the ingest device revision from a repeatable-read snapshot;
metadata_revision changes only after a committed metadata mutation. algorithm_version
identifies derivation semantics. All results recalculate from full stored samples on read,
so late backfill can replace journey IDs, boundaries and historic statistics. Reload analytics
on every ingest SSE revision, even if affected_dates excludes the currently shown journey;
place writes reload analytics and the selected detail. Details and list must use matching
revision pair before combining them. No raw points are rewritten or removed by this API.
Calculation runs on one bounded background worker, preserving ingest/SSE responsiveness.
Concurrent analytics requests receive 429 analytics_busy with Retry-After: 1; a 20-second
calculation timeout returns 503 analytics_timeout. The bounded database read also has an
8-second statement timeout. No queue of raw archives or parallel unbounded workers exists.
The calculation worker has a 1024 MiB old-generation heap limit; the main process temporarily
retains the bounded database snapshot and the structured clone. Budget at least 1.5 GiB of
available RAM for a maximum-size request rather than treating the worker limit as total RSS.
The detail point cap also bounds browser payloads. These are safety ceilings, not pagination.

## Recorded time and raw observation coverage

AnalyticsResponse additionally includes
`recorded_time_derivation:{algorithm_version:"routebook-recorded-time-1",gap_seconds:30}`.
`totals` and every `days[]`, `weeks[]` and `months[]` row include `recorded_seconds:number`
and `point_count:number`. Counts include every archived record whose measurement lies inside
the selected half-open Warsaw bounds, including stationary/isolated samples and distinct raw
records sharing a timestamp. Context records do not count. Weeks/months/totals sum daily
counts; counts are independent of journey selection and indicate empty observation days.

Recorded time follows CONTRACT.md's per-source-segment timestamp intervals and interval
union, using the same full repeatable-read source snapshot as other analytics. All archived
observations are eligible, including stationary parking>=600s, null speeds and geometries
excluded from distance/speed analysis. Existing `elapsed_seconds`, moving/stopped/unknown
seconds, journeys, visits and averages retain their journey-only meaning. Do not add the
new recorded time to journey elapsed or to visits. No per-journey recorded time is exposed.
The bounded 24-hour analytics context includes the required 30-second boundary context;
recorded time clips to selected dates and never includes outer context in totals.

Numeric zero means no qualifying interval, while absent/null means unavailable for old
backend compatibility. A failed calculation retains the existing error response. Existing
analytics limits, worker concurrency/timeouts and revision-pair rules remain in force.
The normative TypeScript response uses required numeric fields for the new backend; clients
supporting older backends must also tolerate missing/null recorded metrics and point counts.
