import type { ClockState, Day, Latest, Point, Segment, Stop } from "./types";
export const km = (meters: number) =>
  new Intl.NumberFormat("pl-PL", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(meters / 1000);
export const localDate = (instant: string | number) =>
  new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Europe/Warsaw",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date(instant));
export const time = (instant: string) =>
  new Intl.DateTimeFormat("pl-PL", {
    timeZone: "Europe/Warsaw",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(instant));
export function dates(from: string, to: string): string[] {
  const valid = (s: string) =>
    /^\d{4}-\d{2}-\d{2}$/.test(s) &&
    !Number.isNaN(Date.parse(s + "T00:00:00Z")) &&
    new Date(s + "T00:00:00Z").toISOString().slice(0, 10) === s;
  if (!valid(from) || !valid(to) || from > to)
    throw Error("Wybierz poprawny zakres dat.");
  const count = Math.round((Date.parse(to) - Date.parse(from)) / 86400000) + 1;
  if (count > 366) throw Error("Zakres może obejmować maksymalnie 366 dni.");
  return Array.from({ length: count }, (_, i) =>
    new Date(Date.parse(from) + i * 86400000).toISOString().slice(0, 10),
  );
}
export function clockState(
  latest: Latest | undefined,
  elapsed: number,
): ClockState {
  if (!latest?.point) return "empty";
  const age =
    Date.parse(latest.server_time) +
    elapsed -
    Date.parse(latest.point.measured_at);
  return age < 0 ? "future_clock" : age <= 30000 ? "fresh" : "stale";
}
export const clockLabel = {
  empty: "Brak pozycji",
  future_clock: "Ostrzeżenie zegara",
  fresh: "Na żywo",
  stale: "Ostatnio widziany",
};
export type Geometry = {
  segments: Segment[];
  stops: Stop[];
  gaps: { from: string; to: string }[];
  points: number;
};
export function geometry(day: Day, simplified = false): Geometry {
  const segments = simplified ? sampleSegments(day.segments) : day.segments;
  const gaps: Geometry["gaps"] = [];
  for (let i = 1; i < day.segments.length; i++) {
    const prev = day.segments[i - 1].points.at(-1),
      next = day.segments[i].edge_from ?? day.segments[i].points[0];
    if (
      prev &&
      next &&
      Date.parse(next.measured_at) > Date.parse(prev.measured_at)
    )
      gaps.push({ from: prev.measured_at, to: next.measured_at });
  }
  return {
    segments,
    stops: day.stops,
    gaps,
    points: segments.reduce((n, s) => n + s.points.length, 0),
  };
}
export function sampleSegments(segments: Segment[], target = 1000): Segment[] {
  const total = segments.reduce((n, s) => n + s.points.length, 0);
  if (total <= target) return segments;
  // Endpoints are mandatory even when their count exceeds the budget.
  const mandatory = segments.reduce(
    (n, s) => n + Math.min(s.points.length, 2),
    0,
  );
  const interior = Math.max(0, target - mandatory);
  const pool = total - mandatory;
  let consumed = 0,
    selected = 0;
  return segments.map((s) => {
    const points: Point[] = [];
    s.points.forEach((p, i) => {
      if (i === 0 || i === s.points.length - 1) {
        points.push(p);
        return;
      }
      consumed++;
      const quota = Math.floor((consumed * interior) / pool);
      if (quota > selected) {
        points.push(p);
        selected = quota;
      }
    });
    return { ...s, points };
  });
}
export function partialMonth(month: string, from: string, to: string) {
  const end = new Date(
    Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5)), 0),
  )
    .toISOString()
    .slice(0, 10);
  return from > month + "-01" || to < end;
}

export function observationGaps(
  parts: Record<string, Geometry>,
): Geometry["gaps"] {
  const runs = Object.entries(parts)
    .sort(([a], [b]) => a.localeCompare(b))
    .flatMap(([, g]) => g.segments);
  const gaps: Geometry["gaps"] = [];
  for (let i = 1; i < runs.length; i++) {
    const previous = runs[i - 1],
      next = runs[i];
    if (previous.run_id === next.run_id) continue;
    const from = previous.points.at(-1),
      to = next.edge_from ?? next.points[0];
    if (
      from &&
      to &&
      Date.parse(to.measured_at) > Date.parse(from.measured_at) &&
      dates(localDate(from.measured_at), localDate(to.measured_at)).every(
        (date) => date in parts,
      )
    )
      gaps.push({ from: from.measured_at, to: to.measured_at });
  }
  return gaps;
}
