# Passive radio power observations

The existing `HomeMonitorService` owns a separate passive observer. It binds only the
Toolkit service and obtains module **0**, then registers callback **50** (candidate ACC)
and **1** (candidate MCU state). Module 0 exposes only register transaction 3 and unregister
transaction 4 in this adapter. Toolkit transaction 1 obtains the module; the observer has
no module command transaction 1 path. Gate module 2, wake-cycle detection, action leases,
rearming, call safety and the existing GPS subscription are independent.

Archived vendor source associates update 50 with `sAccOn` and update 1 with a state used
by `mcuOn`. This is static evidence for diagnostic candidates. It does **not** establish
that MCU state equals physical radio power. The production mapping registry is empty:
`diagnostic-v1` always interprets the raw pair as **UNKNOWN**. No physical verification is
claimed by the implementation or its synthetic tests.

## Freshness and generations

Each bind has a new random observer epoch and a sequence starting at zero. Binding,
disconnect, Binder death, null binding and teardown emit `observer_lost`, with null raw
states. A full-system boot has a durable UUID keyed by Android `BOOT_COUNT`; the same
boot reuses that UUID across process restarts. An unavailable boot identity disables the
observer rather than inventing continuity.

On bind and every thirty seconds, the observer requests both current callback snapshots
using registration `updateNow=1`. Both callbacks must arrive after that request, within one
second of each other. A changed unsolicited half-pair first becomes UNKNOWN, then requests
both fresh values; a mixed old/new pair cannot establish OFF. Missing replies do not refresh
cached data. Any raw component older than forty-five seconds becomes UNKNOWN. No Android
screen event, ACC alone, GPS gap or vendor sleep counter establishes radio OFF.

Every complete pair, including unchanged snapshots, carries its own UTC wall timestamp,
elapsed-realtime timestamp, boot UUID, observer epoch, sequence and immutable event UUID.
The first snapshot is evidence starting at its receipt, never a retrospective transition.

## Explicit profile activation after physical validation

There is no production profile bundled with the APK. After physical testing has independently
proved the meaning of both states for the exact installed firmware, an operator may install
`radio-power-profile.json` in private no-backup app storage. This task does not install one.
The format is strict and fails back to diagnostic UNKNOWN on invalid data:

```json
{
  "version": 1,
  "profile_id": "synthetic-audit-v1",
  "firmware_id": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
  "validation_id": "synthetic-physical-evidence-record",
  "physically_verified": true,
  "mappings": [
    {"acc_state": 0, "module1_state": 0, "radio_state": "OFF"},
    {"acc_state": 0, "module1_state": 1, "radio_state": "ON"}
  ]
}
```

These pairs are invented schema examples, **not an approved mapping**. Every mapping needs
both integer raw states; null values and unmapped pairs remain UNKNOWN. Give each audited
mapping a new immutable profile ID and retain its physical evidence under `validation_id`.
The Home Dev registry independently needs the same profile ID, firmware ID and exact raw
pairs with its own audit record. An APK-supplied profile name never grants server trust.
Removing the private file restores diagnostic-only behavior on the next service start.

`firmware_id` is `sha256:` followed by lowercase SHA-256 of these exact UTF-8 bytes:

```text
dudu-power-firmware-v1\n
<exact Build.FINGERPRINT>\n
<com.syu.ms decimal long versionCode>\n
<sorted lowercase SHA-256 signing-certificate digests joined with comma>\n
```

The displayed `\n` means one LF byte; angle-bracket descriptions are replaced by values.
The certificate digests use the installed package's current APK contents signers. No spaces
are inserted. An Android OTA, vendor version change or signer change therefore invalidates
the private profile. If vendor metadata is unavailable, `firmware_id` is `unavailable` and
profile loading is disabled. Neither raw build strings nor signing details enter logs.

Physical calibration must cover true radio OFF/ON, ACC OFF while the radio remains ON,
screen OFF while audio/radio remains ON, GPS delivery during a stop, Binder loss, and a full
sleep/wake. Preserve the original candidate source evidence separately from physical results.

## Local manual session time

Only a validated mapped radio OFF may exclude time. OFF must remain stable for three
seconds, starting at the first fresh OFF receipt. An interval also requires two fresh,
non-mock GPS observations at most fifteen seconds apart, both with speed at most 0.7 m/s,
accuracy at most 25 m and displacement at most 15 m. Motion closes the OFF coverage and
requires a new trusted OFF observation before exclusion can resume. Unknown, expired,
incomplete or contradicted power evidence leaves ordinary GPS-observed time countable.

Intervals awaiting the three-second decision remain provisional in RAM, for at most that
decision window. Checkpoints contain committed totals only; no later event subtracts a
durable value. A false OFF counts normally. Manual Pause or another continuity boundary
counts any still-unsettled interval conservatively. Reset starts a new timer generation;
old queued observations cannot alter it. Active sessions naturally count again on known ON;
an explicitly paused session remains paused. Distance and raw GPS collection never use this
gate. Restart starts UNKNOWN with a fresh GPS anchor and cannot bridge a former OFF period.

## Independent durable upload

Raw GPS collection, v1 bodies and `routebook.sqlite` schema 1 remain unchanged. A separate
FULL-sync `radio-power.sqlite` stores immutable v2 events under the existing device UUID.
Its disk queue, HTTPS worker, ACK validation and backoff are independent of GPS delivery.
Both use the already-provisioned endpoint and credentials; no new account or configuration
is needed for diagnostic observations. Power delivery starts at service initialization even
when no GPS records are pending. Every pass makes at most four requests, alternating latest
and at most 200 oldest pending events, then yields before continuing. Continuous arrivals
cannot starve the backlog. A GPS or power HTTP error cannot block the other uploader.

Lost/invalid responses retain immutable events. A fully validated accepted/duplicate result
deletes only its event; a rejected result quarantines only that event. v2 rejects use
`invalid_observation` or `event_conflict`. No request body, coordinates or credential is logged.
See the checked-in Routebook v2 contract for envelope and ACK details. An old APK can reopen
its unchanged v1 GPS queue after rollback and simply ignores the separate power database.

Pure checks cover trust/profile binding, debounce, motion contradiction, GPS qualification,
expiry, epoch, pause/reset boundaries and restart. Emulator-only instrumentation covers
SQLite rollback compatibility, immutable power persistence, upload loss/invalid ACKs,
per-event rejection, fair backlog and failure isolation. These checks do not validate
module 0 behavior or the physical meaning of either candidate callback on a radio.
