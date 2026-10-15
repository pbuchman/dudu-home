import type {
  AnalyticsPoint,
  JourneyDetailResponse,
} from "../../contract/analytics.js";
export function duration(seconds: number | null | undefined): string {
  if (seconds == null) return "Brak danych";
  const rounded = Math.round(seconds);
  if (rounded < 60) return `${rounded} s`;
  if (rounded < 3600)
    return `${Math.floor(rounded / 60)} min${rounded % 60 ? ` ${rounded % 60} s` : ""}`;
  return `${Math.floor(rounded / 3600)} godz. ${Math.floor((rounded % 3600) / 60)} min`;
}
export const speed = (mps: number | null | undefined): string =>
  mps == null
    ? "Brak danych"
    : `${new Intl.NumberFormat("pl-PL", { maximumFractionDigits: 1 }).format(mps * 3.6)} km/h`;
export function periodBounds(date: string, period: "day" | "week" | "month") {
  const value = new Date(`${date}T12:00:00Z`);
  if (period === "week")
    value.setUTCDate(value.getUTCDate() - ((value.getUTCDay() + 6) % 7));
  if (period === "month") value.setUTCDate(1);
  const from = value.toISOString().slice(0, 10);
  if (period === "week") value.setUTCDate(value.getUTCDate() + 6);
  if (period === "month") value.setUTCMonth(value.getUTCMonth() + 1, 0);
  return { from, to: value.toISOString().slice(0, 10) };
}
export function nearestObservation(
  points: AnalyticsPoint[],
  lon: number,
  lat: number,
) {
  let nearest = 0,
    distance = Infinity;
  points.forEach((p, i) => {
    const next =
      ((p.lon - lon) * Math.cos((lat * Math.PI) / 180)) ** 2 +
      (p.lat - lat) ** 2;
    if (next < distance) {
      nearest = i;
      distance = next;
    }
  });
  return nearest;
}
export function sameRevision(
  a: JourneyDetailResponse,
  b: {
    source_revision: number;
    metadata_revision: number;
    algorithm_version: string;
  },
) {
  return (
    a.source_revision === b.source_revision &&
    a.metadata_revision === b.metadata_revision &&
    a.algorithm_version === b.algorithm_version
  );
}
/** Move only through observations; a disconnected run is jumped, never interpolated. */
export function replayStep(
  detail: JourneyDetailResponse,
  cursor: number,
  clock: number,
  runByPoint?: Map<string, string>,
) {
  const points = detail.points;
  let next = cursor;
  const runs =
    runByPoint ??
    new Map(
      detail.segments.flatMap((s) =>
        s.points.map((p) => [p.event_id, s.run_id] as const),
      ),
    );
  let skipped = 0;
  while (next + 1 < points.length) {
    const nextPoint = points[next + 1];
    if (runs.get(points[next].event_id) !== runs.get(nextPoint.event_id)) {
      skipped = Math.max(
        0,
        (Date.parse(nextPoint.measured_at) -
          Date.parse(points[next].measured_at)) /
          1000,
      );
      next++;
      clock = Date.parse(nextPoint.measured_at);
      break;
    }
    if (Date.parse(nextPoint.measured_at) > clock) break;
    next++;
  }
  return { cursor: next, clock, skipped };
}
