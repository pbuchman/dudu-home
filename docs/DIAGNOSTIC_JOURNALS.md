# Local diagnostic journals

The approved October 16 diagnostic history is independent of action reservations. A waiting,
failed or user-cancelled occurrence now leaves durable diagnostic evidence. This supersedes
the older RAM-only diagnostic buffer and discarded pre-send cancellation history. It does
not change any quota, command admission, cancellation, priority, cleanup lease or retry rule.
The action queue itself remains in memory and is never restored from a journal.

## Generic action evidence

Private no-backup files are `diagnostics.txt`, `diagnostics.previous.txt` and
`diagnostics.previous.txt.2` through `.7`: at most eight files of one MiB each. Each
ASCII line keeps the compatible `wall_ms elapsed_ms` prefix, then `PROCESS`, `SCHEMA=2`,
process-local `SEQ`, category and bounded fields. `ID` is a process-bound occurrence ID,
not an authorization token. Together process and ID identify an attempt across a capture.
Sequence describes append order; the leading timestamps describe observation time, so
asynchronously written general events may have an earlier timestamp than a nearby row.

Attempt transitions and queue-block changes append, flush and fsync before returning.
An interrupted last append is truncated only to its last complete newline before new rows
are appended; complete earlier rows survive. Initial retention inspection reads no more
than eight bounded segments, with no unbounded input stream or history walk. Actual radio
filesystem/fsync latency remains a hardware check. The scheduler refreshes monotonic time
after synchronous observations and before selection; diagnostic IO cannot extend a queued
5/120-second deadline. The unchanged gate executor independently rechecks fresh phone idle
before its own dial, while predispatch-marker latency still needs radio observation.
`COMMAND_DISPATCH STATE=SENDING` is written after the existing reservation succeeds and
before the caller can send its command. This marker indicates permission to send, not
successful delivery, a phone call, playback, cleaning or a physical gate opening.
General monitor categories retain the bounded background writer. `diagnostic_pending` and
`Diagnostics.droppedWrites()` expose its queue/disk failures. Storage failure emits only
`DIAGNOSTICS_WRITE_FAILED` and increases that counter; persistence is unavailable for that
row. It never changes a reservation or creates a retry/rearm or new admission blocker.
Abrupt power loss during a filesystem operation still has normal filesystem limitations.

`QUEUE_STATE` records action, state, reason, blocker occurrence ID and blocker action.
Observed reasons include `MENU_VISIBLE`, `ACTIVE_OPERATION`, `RESOURCE_CLEANUP`,
`UI_REQUEST_PENDING`, `PRESENTATION_BUSY`, `PRIORITY`, `GATE_PRECONDITION`,
`YANOSIK_GRACE`, `CLEANING_FEEDBACK`, `EXPIRED` and `FAILURE`. `BLOCKS` preserves simultaneous causes:
bit 0 cleanup, 1 active operation, 2 pending UI request, 3 busy presentation, 4 visible menu,
5 gate precondition, 6 priority, 7 Yanosik grace and 8 cleaning feedback. `REASON` selects the first set bit. The
collector reports all set bits. A predicate-ready queue entry records `READY`, not a blocked
`WAITING/NONE`. Missing blocker IDs are explicitly zero, rather
than guessed. Cleanup may remain after a terminal UI state. An identical ongoing block
for the same attempt is recorded at most once per minute; changed reasons record immediately.
The observers read the existing scheduler predicates; they do not add Wispr admission rules,
Spotify delays or a different scheduling order.

Fields have a closed vocabulary and numeric or bounded enum values. Never pass a phone
number, coordinate, street, device address, credential, login, transcript, token, arbitrary
exception message or HTTP payload to the generic API. Those data are not generic categories.

The existing read-only collector now inventories all eight generic segments:

```sh
python3 scripts/automation-diagnostics.py collect --serial "$RADIO_SERIAL" --output "$PRIVATE_CAPTURE"
python3 scripts/automation-diagnostics.py summarize "$PRIVATE_CAPTURE"
python3 scripts/automation-diagnostics.py summarize "$PRIVATE_CAPTURE" \
  --from '2026-11-18T14:00:00+01:00' --to '2026-11-18T15:00:00+01:00'
```

Its destination must be a new directory under an owner-only directory outside Git. It does
not start/stop an app, dispatch actions, inspect firmware logcat or collect location traces.
A summary reconstructs app observations, process/attempt timelines and observed queue blockers.
Time filters accept ISO 8601 strings with explicit UTC offsets or `Z`.
`--from` includes its boundary and `--to` excludes it. Both also work with `collect` to
filter the saved summary. A collection still retains the available validated segments
within their existing size limits, so another window can be investigated locally.

The filtered summary shows all retained rows in the requested window, including startup,
cycle, motion and target-app snapshots that have no attempt ID. It displays the radio's
wall-clock timestamp in `Europe/Warsaw` with its offset. Process, sequence, elapsed time,
startup ID and attempt ID stay visible where recorded. The device clock can change. The
monitor dump reflects capture time and does not establish the state at the time of a row.
A zero request count means the journal contains no media request marker in that interval.
An internal sequence gap indicates missing values between available rows. It cannot distinguish
read races, rotation, retention, writer drops and clock changes. Older journals may lack this
information. A missing rotated segment is accepted. A readable malformed segment fails validation.

`MOTION_STATE` records the current detection branch, its gate/startup/cancellation conditions
and elapsed evidence. `MOTION_FIX` gives coarse accuracy, age, speed and cadence bands
and separate Yanosik/Spotify eligibility. `MEDIA_TARGET_SNAPSHOT` describes only permission,
service-notification and media-session categories. `MEDIA_RECENT_FOREGROUND` includes at most
an allowlisted app category and age band, subject to the radio's existing access. A missing or
unknown snapshot remains ambiguous. `MEDIA_ENQUEUE_SUPPRESSED`, the queue blockers and
action result rows distinguish movement recognition, requests and resolutions.

### Startup and post-motion summary in 1.5.1-rc1

`GPS_STARTUP` adds category-only timings to schema 2. `PROCESS`, `STARTUP` and
`OBSERVATION` identify the monitor's observation. `OBSERVATION=0` begins at service creation.
A verified increasing wake counter opens a new observation on the running service.
`ELAPSED_MS` is monotonic time from the beginning of that observation. Durations may
span elapsed time before `--from`, but the summary includes only startup records whose wall
timestamps fall in the window. A `CYCLE_READ` row shows the observed counter or `-1` if
unavailable. Unrecorded cycle values remain unknown. An archive commit is attributed to
the observation that supplied the callback, even if its SQLite write completes during a
later observation.

- `STARTED`, `CYCLE_READ` and the subscription stages show monitor creation or verified wake,
  cycle-read outcome, successful or denied GPS subscription and resubscription attempts.
- `NO_CALLBACK` is a positive watchdog observation that the service received zero GPS
  callbacks from the start of that observation through the recorded check. A missing
  `FIRST_CALLBACK`, absent monitor dump, sequence gap, older export or clipped window leaves
  this timing unknown. `FIRST_CALLBACK STATE=EMPTY` records delivery of an empty callback.
- `MOTION_FIX_REJECTED` carries a bounded reason for discarding a callback. A
  `FIRST_USABLE_MOTION_FIX` may describe a stationary or candidate fix. `ACCURACY_OVERLAP`
  means a quality-qualified candidate still needs more displacement. `MOTION_CONFIRMED`
  records the later detector milestone. `FIRST_ARCHIVE_COMMIT` marks the independent local
  SQLite commit and says nothing about upload delivery.

`MEDIA_ENQUEUE_SUPPRESSED STAGE=MOTION_CONFIRMED` directly records why the enqueue step
was blocked after motion confirmation. `GATE_STALE` describes the gate freshness check.
`SESSION_UNAVAILABLE` describes the journey session. `DEPARTURE_CONTINUATION_REJECTED`
records the applicable attempt or cycle guard, and `MEDIA_GATE_RESULT REASON=AREA_CHANGED`
records a success callback whose captured gate area no longer matched. A `MEDIA_ENQUEUED`
row ties an actual Yanosik or Spotify queue entry to its source, startup observation and
vendor cycle where available. Subsequent queue or action rows can share that context via
the same `PROCESS` and `ID`. The value `-1` denotes unavailable context. Older rows with no
matching enqueue marker remain standalone observations.

An expiry can cite an earlier `GATE_PRECONDITION` wait for the same attempt. The journal
shows those two observations and leaves the blocker at expiry unknown. A matching enqueue
or wait before `--from` is shown as earlier context. The summary counts distinct attempts
with a `SPOTIFY_LAUNCH_REQUESTED` marker in the window. Those markers report requests by
Dudu Home. Visible Spotify launches and audible playback require separate evidence.

The collector reads aggregate Routebook queue, filter and delivery health only through known
dump fields and journal categories. Provisioning identifiers and rows with unknown dump fields
are excluded. Generic records contain no private location trace, session IDs from other apps,
track metadata, notification contents or route payloads. App observations do not establish
foreground presentation, audible playback or physical activity.

## Private place decision and display evidence

`trip-place-trace.jsonl`, `trip-place-trace.previous.jsonl` and suffixes `.2` through `.7`
are a separate owner-private journal, at most eight files of four MiB (32 MiB total).
Schema 2 is active by default under the user's diagnostic authorization. An owner-managed
`trip-place-trace.disabled` file in private no-backup storage suppresses new records; the
legacy `.enabled` flag is no longer required. No UI/export endpoint, upload or automatic
publication is added. This journal never enters logcat, the generic collector or Routebook.

Rows contain process/sequence and observation wall/monotonic time, exact fix monotonic time,
receive time, GPS source, validity/mock/quality inputs, coordinates, resolver generation,
latency, raw locality/street decision, displayed labels and qualities, candidate scores,
map generation and supported-heading evidence when available. Street raw quality and retained
display quality remain distinct. `fix_wall_estimated_ms` is explicitly derived from the
wall/monotonic pair, not a recovered original GPS UTC timestamp. Exact fix elapsed time and
coordinates permit correlation with the same shared GPS fix and the private route; no second
GPS subscription or action lease is introduced. Generation/reset events reject stale results
independently of these observations.

`presentation` rows describe actual surface rendering/visibility and controller reset
transitions, including GPS expiry/provider/new-journey resets without a resolver result.
They record the rendered title/subtitle, actual generation and optional exact immutable fix
reference. A reset with no fix stores null rather than presenting the previous fix as fresh.
Identical surface/visibility/labels/generation are coalesced across subsequent fixes/frames;
changed labels, hide/show and generation changes record independently of raw resolver rows.
All presentation rows share this same 32 MiB budget.

Rows/labels and the pending writer are bounded (32 pending tasks, 16 KiB maximum row, 256
characters per label). Dropped rows are counted in subsequent trace rows. This lane performs
filesystem IO asynchronously and cannot promise the final in-flight row survives process death.
It must remain private when copied by an authorized operator: it contains actual locations
and displayed address labels and is not suitable for public test evidence or release assets.

Both journals expire rows older than seven wall-clock days when a new row is appended.
Atomic expiry replacement also respects the file/byte budget: if all eight segments are
occupied, the oldest other segment may be evicted before creating its bounded replacement.
An orphaned replacement from interrupted expiry is removed on the next write. A
stopped application cannot execute deletion; a dormant archive is pruned on its next write.
Wall-clock rollback retains future-dated rows conservatively, while byte/file limits still
apply. Do not infer seven days of continuous coverage from the retention setting.

## Verification boundary

`bash scripts/check-diagnostics.sh` runs synthetic host checks for append visibility before
any dispatcher, restart, hard byte/file bounds, row expiry, block coalescing, symlink/storage
failure interrupted-tail recovery, combined blocker causes and invalid-field rejection. Python fixtures reconstruct eight segments, process IDs
and each block cause without ADB or real locations. They also cover watchdog evidence,
callback rejection, independent SQLite commit, window clipping, legacy exports, attempt
correlation and repeated Spotify requests. Instrumented cancellation checks retain
pre-send evidence while verifying zero reservations, one-shot sending and unchanged cleanup.
These checks do not establish radio storage latency, real queue incident reconstruction,
actual DUDU Binder behavior, GNSS accuracy, physical rendering or driving/wake acceptance.
