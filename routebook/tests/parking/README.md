# Parking acceptance and independent quality study

All committed examples and tests are invented. No independent reference dataset has
been supplied, so the 95% field target remains **pending**. Synthetic correctness,
backend parity and browser acceptance cannot establish that target.

Run the dependency-free offline evaluator tests:

```sh
node --test routebook/tests/parking/quality.test.mjs
```

Create the example and real study file outside **every Git checkout**, using an
owner-only private directory. The CLI refuses input located inside a repository,
resolves symbolic links before checking and never prints source data on errors.
It makes no network requests and its report contains only aggregate counts and the
algorithm identifier/hash. Do not put coordinates, raw recordings, names, device IDs
or credentials into this format or commit private inputs/reports.

```sh
node routebook/scripts/evaluate-parking-quality.mjs --example > /private/study-example.json
node routebook/scripts/evaluate-parking-quality.mjs /private/study-example.json
```

Replace `/private` with an existing writable private directory. `--example` emits
synthetic schema-1 JSON, with these required fields:

- `synthetic`: true for any invented or test dataset. Synthetic reports can never PASS.
- `algorithm`: frozen version, SHA-256 of the reviewed frozen rules, and UTC `frozen_at`.
- `reference`: explicit `independent: true`, `used_for_calibration: false`,
  `complete_detection_stream: true`, and UTC `collection_started_at` after freeze.
  The operator must establish these facts from independent reference evidence;
  this tool checks the supplied declarations, not the physical recording process.
- `detections`: the complete chronological detection stream, sequence 1 onward,
  never selected after seeing the labels. Each has UTC `detected_at`, UTC
  `inferred_off_at`, actual `excluded_seconds`, and `label` (null if unavailable).
- A label has independently observed `radio_off` (true/false/null),
  `reference_off_at` (UTC/null), and `falsely_deducted_seconds` (number/null).
  Real OFF and absolute boundary error <=30 s is correct. False OFF is incorrect,
  and all its deducted seconds must be labelled false. Missing shutdown/boundary
  labels remain unknown. False deducted time is measured independently, including
  boundary mismatches; null is unknown and never silently means zero.
- `missed_parkings`: independently labelled real shutdowns missed by detection,
  each with unique positive `reference_sequence` and UTC `radio_off_at`. They are
  reported separately and must cover the same reference observation window.

Only the first 100 detections determine the gate. Later cases cannot rescue the
result. Fewer than 100 or any unknown correctness labels => PENDING. At least 95
correct of the fully labelled first 100 => PASS, otherwise FAIL. Precision uses
all selected detections as denominator, retaining unknowns and marking the result
as a lower bound. The report separately shows unknown false-deduction labels and
the known false deducted total; do not present that partial total as complete.

The evaluator exits 2 on invalid/unsafe input, otherwise 0 after printing a report.
Consumers must inspect `status` and `target_achieved`, not infer PASS from exit 0.
