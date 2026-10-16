import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm, mkdir, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { evaluateStudy, syntheticExample } from '../../scripts/evaluate-parking-quality.mjs';

function study(length = 100) {
  const input = syntheticExample();
  input.detections = Array.from({ length }, (_, index) => ({ ...structuredClone(input.detections[0]), sequence: index + 1 }));
  return input;
}
test('synthetic evidence can never establish the field target, including 100 perfect cases', () => {
  const result = evaluateStudy(study());
  assert.equal(result.status, 'SYNTHETIC_ONLY'); assert.equal(result.target_achieved, false);
  assert.equal(result.precision, 1); assert.equal(result.correct, 100);
});
test('under 100 and unknown reference labels remain pending, never successes', () => {
  const input = study(99); input.synthetic = false;
  assert.equal(evaluateStudy(input).status, 'PENDING');
  input.detections.push({ ...input.detections[0], sequence: 100, label: null });
  let result = evaluateStudy(input);
  assert.equal(result.status, 'PENDING'); assert.equal(result.correct, 99); assert.equal(result.unknown, 1);
  assert.equal(result.precision, .99); assert.equal(result.precision_is_lower_bound, true);
  input.detections[99].label = { radio_off: true, reference_off_at: null, falsely_deducted_seconds: null };
  result = evaluateStudy(input); assert.equal(result.unknown, 1); assert.equal(result.falsely_deducted_time_unknown_count, 1);
});
test('strict 30-second boundary and real OFF rule; false deduction and missed parking are separate', () => {
  const input = study(); input.synthetic = false;
  for (let i = 0; i < 5; i++) input.detections[i].label = { radio_off: false, falsely_deducted_seconds: 300 };
  input.detections[5].label.reference_off_at = '2026-01-02T00:05:30Z';
  input.missed_parkings = [{ reference_sequence: 101, radio_off_at: '2026-01-02T01:00:00Z' }];
  let result = evaluateStudy(input);
  assert.equal(result.status, 'PASS'); assert.equal(result.correct, 95); assert.equal(result.incorrect, 5);
  assert.equal(result.precision, .95); assert.equal(result.falsely_deducted_seconds_known, 3400);
  assert.equal(result.independently_labelled_missed_parkings, 1);
  input.detections[5].label.reference_off_at = '2026-01-02T00:05:30.001Z';
  result = evaluateStudy(input); assert.equal(result.status, 'FAIL'); assert.equal(result.correct, 94);
});
test('first 100 consecutive detections are fixed; later successes cannot rescue a failure', () => {
  const input = study(200); input.synthetic = false;
  for (let i = 0; i < 6; i++) input.detections[i].label = { radio_off: false, falsely_deducted_seconds: 300 };
  const result = evaluateStudy(input);
  assert.equal(result.status, 'FAIL'); assert.equal(result.ignored_later_detections, 100); assert.equal(result.correct, 94);
  input.detections[1].sequence = 3;
  assert.throws(() => evaluateStudy(input), /consecutive/);
});
test('reference independence, non-calibration data and frozen rules are required', () => {
  for (const mutate of [
    x => { x.reference.independent = false; }, x => { x.reference.used_for_calibration = true; },
    x => { x.reference.complete_detection_stream = false; },
    x => { x.algorithm.frozen_at = '2026-02-01T00:00:00Z'; },
    x => { x.algorithm.rules_sha256 = 'not-a-hash'; },
    x => { x.detections[1].detected_at = '2026-01-01T00:00:00Z'; },
    x => { x.detections[0].coordinates = [0, 0]; },
    x => { x.detections[0].label = { radio_off: false, falsely_deducted_seconds: 0 }; },
  ]) { const input = study(); mutate(input); assert.throws(() => evaluateStudy(input)); }
});
test('offline CLI reads private files, refuses Git trees/symlinks and never echoes rejected input', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'routebook-quality-synthetic-'));
  const cli = new URL('../../scripts/evaluate-parking-quality.mjs', import.meta.url);
  try {
    const file = join(dir, 'study.json'); await writeFile(file, JSON.stringify(study(1)));
    let child = spawnSync(process.execPath, [cli.pathname, file], { encoding: 'utf8' });
    assert.equal(child.status, 0); assert.equal(JSON.parse(child.stdout).status, 'SYNTHETIC_ONLY');
    await mkdir(join(dir, '.git')); child = spawnSync(process.execPath, [cli.pathname, file], { encoding: 'utf8' });
    assert.equal(child.status, 2); assert.equal(child.stdout, '');
    const link = join(tmpdir(), `routebook-quality-link-${process.pid}.json`);
    await symlink(file, link);
    try { assert.equal(spawnSync(process.execPath, [cli.pathname, link], { encoding: 'utf8' }).status, 2); }
    finally { await rm(link); }
    await rm(join(dir, '.git'), { recursive: true });
    await writeFile(file, '{"private_marker_DO_NOT_ECHO":');
    child = spawnSync(process.execPath, [cli.pathname, file], { encoding: 'utf8' });
    assert.equal(child.status, 2); assert.ok(!child.stderr.includes('private_marker')); assert.ok(!child.stderr.includes(dir));
  } finally { await rm(dir, { recursive: true, force: true }); }
});
