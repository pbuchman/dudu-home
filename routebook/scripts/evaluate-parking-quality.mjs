#!/usr/bin/env node
/** Offline reference-study gate. Input and labels stay outside every checkout. */
import { readFile, realpath, lstat } from 'node:fs/promises';
import { resolve, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const timestamp = value => typeof value === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{3})?Z$/.test(value) && Number.isFinite(Date.parse(value));
function requireThat(condition, message) { if (!condition) throw Error(message); }
function fields(value, allowed, name) {
  requireThat(value && typeof value === 'object' && !Array.isArray(value), `${name} must be an object`);
  requireThat(Object.keys(value).every(key => allowed.includes(key)), `${name} has unsupported fields`);
}
const nonnegative = value => Number.isFinite(value) && value >= 0;

export function evaluateStudy(input) {
  fields(input, ['version', 'synthetic', 'algorithm', 'reference', 'detections', 'missed_parkings'], 'study');
  requireThat(input.version === 1 && typeof input.synthetic === 'boolean', 'version 1 and explicit synthetic flag required');
  fields(input.algorithm, ['version', 'rules_sha256', 'frozen_at'], 'algorithm');
  const algorithm = input.algorithm;
  requireThat(typeof algorithm.version === 'string' && /^[a-zA-Z0-9._-]{1,80}$/.test(algorithm.version), 'invalid algorithm version');
  requireThat(/^[a-f0-9]{64}$/.test(algorithm.rules_sha256) && timestamp(algorithm.frozen_at), 'algorithm hash and UTC freeze time required');
  fields(input.reference, ['independent', 'used_for_calibration', 'complete_detection_stream', 'collection_started_at'], 'reference');
  const reference = input.reference;
  requireThat(reference.independent === true && reference.used_for_calibration === false && reference.complete_detection_stream === true, 'independent, non-calibration, complete consecutive reference stream required');
  requireThat(timestamp(reference.collection_started_at) && Date.parse(reference.collection_started_at) >= Date.parse(algorithm.frozen_at), 'reference collection must begin after algorithm freeze');
  requireThat(Array.isArray(input.detections) && Array.isArray(input.missed_parkings), 'detections and missed_parkings arrays required');
  let previous = Date.parse(reference.collection_started_at);
  const outcomes = input.detections.map((d, i) => {
    fields(d, ['sequence', 'detected_at', 'inferred_off_at', 'excluded_seconds', 'label'], 'detection');
    requireThat(d.sequence === i + 1, 'detections must be consecutive from sequence 1, without sorting or filtering');
    requireThat(timestamp(d.detected_at) && Date.parse(d.detected_at) >= previous, 'detections must be chronological and after reference collection starts');
    previous = Date.parse(d.detected_at);
    requireThat(timestamp(d.inferred_off_at) && nonnegative(d.excluded_seconds), 'inferred UTC boundary and nonnegative excluded_seconds required');
    requireThat(Date.parse(d.inferred_off_at) <= Date.parse(d.detected_at) && Date.parse(d.inferred_off_at) >= Date.parse(reference.collection_started_at), 'inferred boundary must be inside the independent observation period');
    if (d.label === null) return { outcome: 'unknown', falseSeconds: null };
    fields(d.label, ['radio_off', 'reference_off_at', 'falsely_deducted_seconds'], 'label');
    requireThat([true, false, null].includes(d.label.radio_off), 'radio_off must be true, false or null');
    const falseSeconds = d.label.falsely_deducted_seconds ?? null;
    requireThat(falseSeconds === null || (nonnegative(falseSeconds) && falseSeconds <= d.excluded_seconds), 'false deducted seconds must be known nonnegative time within excluded_seconds, or null');
    if (d.label.radio_off === false) {
      requireThat(falseSeconds === d.excluded_seconds, 'a false radio shutdown must label all deducted time as false');
      return { outcome: 'incorrect', falseSeconds };
    }
    if (d.label.radio_off === null || !timestamp(d.label.reference_off_at)) return { outcome: 'unknown', falseSeconds };
    return { outcome: Math.abs(Date.parse(d.inferred_off_at) - Date.parse(d.label.reference_off_at)) <= 30_000 ? 'correct' : 'incorrect', falseSeconds };
  });
  const selected = outcomes.slice(0, 100);
  const count = name => selected.filter(row => row.outcome === name).length;
  for (const missed of input.missed_parkings) {
    fields(missed, ['reference_sequence', 'radio_off_at'], 'missed parking');
    requireThat(Number.isSafeInteger(missed.reference_sequence) && missed.reference_sequence > 0 && timestamp(missed.radio_off_at), 'independently labelled missed parking requires reference sequence and UTC shutdown');
    requireThat(Date.parse(missed.radio_off_at) >= Date.parse(reference.collection_started_at), 'missed parking precedes reference collection');
  }
  requireThat(new Set(input.missed_parkings.map(p => p.reference_sequence)).size === input.missed_parkings.length, 'duplicate missed parking reference sequence');
  const correct = count('correct'), incorrect = count('incorrect'), unknown = count('unknown');
  const complete = selected.length === 100 && unknown === 0;
  const status = input.synthetic ? 'SYNTHETIC_ONLY' : !complete ? 'PENDING' : correct >= 95 ? 'PASS' : 'FAIL';
  return {
    version: 1, algorithm_version: algorithm.version, rules_sha256: algorithm.rules_sha256,
    status, target_achieved: status === 'PASS', synthetic: input.synthetic,
    selected_detections: selected.length, required_detections: 100,
    ignored_later_detections: Math.max(0, outcomes.length - 100), correct, incorrect, unknown,
    precision: selected.length ? correct / selected.length : null,
    // Unknown labels remain in the denominator. This is a conservative lower bound,
    // never the misleading precision among only successfully labelled cases.
    precision_is_lower_bound: unknown > 0,
    falsely_deducted_seconds_known: selected.reduce((sum, row) => sum + (row.falseSeconds ?? 0), 0),
    falsely_deducted_time_unknown_count: selected.filter(row => row.falseSeconds === null).length,
    independently_labelled_missed_parkings: input.missed_parkings.length,
    note: input.synthetic ? 'Synthetic checks do not establish field precision.' : !complete ? 'First 100 consecutive independently labelled detections are not complete.' : 'Result relies on the supplied independent-reference and stream-completeness attestations.',
  };
}

export function syntheticExample() {
  return { version: 1, synthetic: true,
    algorithm: { version: 'routebook-in-car-1', rules_sha256: '0'.repeat(64), frozen_at: '2026-01-01T00:00:00Z' },
    reference: { independent: true, used_for_calibration: false, complete_detection_stream: true, collection_started_at: '2026-01-02T00:00:00Z' },
    detections: [{ sequence: 1, detected_at: '2026-01-02T00:10:00Z', inferred_off_at: '2026-01-02T00:05:00Z', excluded_seconds: 300,
      label: { radio_off: true, reference_off_at: '2026-01-02T00:05:20Z', falsely_deducted_seconds: 20 } }], missed_parkings: [] };
}

async function main(args) {
  if (args.length === 1 && args[0] === '--example') { console.log(JSON.stringify(syntheticExample(), null, 2)); return; }
  requireThat(args.length === 1, 'usage: node scripts/evaluate-parking-quality.mjs /private/study.json | --example');
  const path = await realpath(resolve(args[0]));
  const checkout = await realpath(resolve(dirname(fileURLToPath(import.meta.url)), '../..'));
  const inside = relative(checkout, path);
  requireThat(inside.startsWith('..') || inside.startsWith('/'), 'reference data must remain outside the repository');
  for (let parent = dirname(path); ; parent = dirname(parent)) {
    const git = await lstat(resolve(parent, '.git')).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
    requireThat(!git, 'reference data must remain outside every Git repository');
    if (dirname(parent) === parent) break;
  }
  const input = JSON.parse(await readFile(path, 'utf8'));
  console.log(JSON.stringify(evaluateStudy(input), null, 2));
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch(() => {
    // Never echo source data, filenames or parser excerpts from private input.
    console.error('Invalid study or unsafe input location. Check the documented schema; no private input was printed.');
    process.exitCode = 2;
  });
}
