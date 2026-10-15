import { dates, dayBounds } from './calendar.js';
import type { Snapshot, StoredPoint } from './types.js';

/** Observation continuity, independent of geometry, speed and journey/stop rules. */
export const recordedTimeDerivation = Object.freeze({
  algorithm_version: 'routebook-recorded-time-1' as const,
  gap_seconds: 30 as const,
});
type Interval = { start: number; end: number };

/** Deduplicate timestamps inside each source segment, then union all observed intervals. */
export function recordedIntervals(points: StoredPoint[]): Interval[] {
  const segments = new Map<string, Set<number>>();
  for (const p of points) {
    const key = p.device_id + ':' + p.segment_id;
    let times = segments.get(key);
    if (!times) { times = new Set(); segments.set(key, times); }
    times.add(Date.parse(p.measured_at));
  }
  const intervals: Interval[] = [];
  for (const times of segments.values()) {
    const ordered = [...times].sort((a, b) => a - b);
    for (let i = 1; i < ordered.length; i++) {
      const start = ordered[i - 1], end = ordered[i];
      if (end > start && end - start <= recordedTimeDerivation.gap_seconds * 1000) intervals.push({ start, end });
    }
  }
  intervals.sort((a, b) => a.start - b.start || a.end - b.end);
  const union: Interval[] = [];
  for (const interval of intervals) {
    const last = union.at(-1);
    if (last && interval.start <= last.end) last.end = Math.max(last.end, interval.end);
    else union.push({ ...interval });
  }
  return union;
}

/** Full SQL archive summaries, when present, came from the snapshot's same transaction. */
export function recordedDays(snapshot: Snapshot, from: string, to: string): Record<string, number> {
  const windows = dates(from, to).map(date => ({ date, ...dayBounds(date) }));
  const result = Object.fromEntries(windows.map(w => [w.date, 0]));
  if (snapshot.recorded_days !== undefined) {
    for (const w of windows) result[w.date] = snapshot.recorded_days[w.date] ?? 0;
    return result;
  }
  // Pure derivation is also used by the bounded analytics worker and synthetic stores.
  let windowIndex = 0;
  for (const interval of recordedIntervals(snapshot.points)) {
    while (windowIndex < windows.length && windows[windowIndex].end <= interval.start) windowIndex++;
    for (let i = windowIndex; i < windows.length && windows[i].start < interval.end; i++) {
      const w = windows[i];
      result[w.date] += Math.max(0, Math.min(w.end, interval.end) - Math.max(w.start, interval.start)) / 1000;
    }
  }
  return result;
}

/** Bounded indexed raw timestamps only: no map sampling, speed filters or segment bridging.
 * PostgreSQL multirange union removes overlap before Warsaw half-open day clipping.
 */
export const recordedTimeSql = `WITH timestamps AS (
  SELECT DISTINCT segment_id,measured_at FROM routebook_points
  WHERE device_id=$1 AND measured_at >= $2::timestamptz - interval '30 seconds'
    AND measured_at <= $3::timestamptz + interval '30 seconds'
), adjacent AS (
  SELECT measured_at,lag(measured_at) OVER(PARTITION BY segment_id ORDER BY measured_at) previous_at FROM timestamps
), coverage AS (
  SELECT range_agg(tstzrange(greatest(previous_at,$2::timestamptz),least(measured_at,$3::timestamptz),'[)')) covered
  FROM adjacent WHERE measured_at > previous_at AND measured_at <= previous_at + interval '30 seconds'
    AND measured_at > $2::timestamptz AND previous_at < $3::timestamptz
), intervals AS (
  SELECT unnest(covered) observed FROM coverage
), clipped AS (
  SELECT local_day::date::text date,
    extract(epoch FROM least(upper(observed),(local_day + interval '1 day') AT TIME ZONE 'Europe/Warsaw')
      - greatest(lower(observed),local_day AT TIME ZONE 'Europe/Warsaw')) seconds
  FROM intervals CROSS JOIN LATERAL generate_series(
    date_trunc('day',lower(observed) AT TIME ZONE 'Europe/Warsaw'),
    date_trunc('day',(upper(observed)-interval '1 microsecond') AT TIME ZONE 'Europe/Warsaw'),
    interval '1 day') local_day
)
SELECT date,sum(seconds)::double precision recorded_seconds FROM clipped GROUP BY date ORDER BY date`;
