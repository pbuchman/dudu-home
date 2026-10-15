import type {
  AnalyticsPoint,
  JourneyDetailResponse,
  Metrics,
  NamedPlace,
} from "../../contract/analytics.js";
import type { AnalyticsResponse } from "../src/recorded-time";
export const device = "6b8f2886-ccbd-5b1d-b014-3fa94cc0a1ea";
export const home: NamedPlace = {
  place_id: "10000000-0000-4000-8000-000000000001",
  name: "Miejsce Alfa",
  lat: 0,
  lon: 0,
  radius_m: 100,
};
export const destination: NamedPlace = {
  place_id: "10000000-0000-4000-8000-000000000002",
  name: "Miejsce Beta",
  lat: 0,
  lon: 0.01,
  radius_m: 100,
};
const timestamp = (second: number) =>
  new Date(Date.parse("2026-10-05T08:00:00Z") + second * 1000).toISOString();
const seconds = [0, 10, 20, 80, 90, 100];
export const points: AnalyticsPoint[] = seconds.map((s, i) => ({
  event_id: `20000000-0000-4000-8000-00000000000${i}`,
  segment_id: `30000000-0000-4000-8000-00000000000${i < 3 ? 1 : 2}`,
  device_id: device,
  measured_at: timestamp(s),
  received_at: timestamp(s + 1),
  lat: 0,
  lon: i * 0.002,
  accuracy_m: 5,
  speed_mps: i === 4 ? null : i === 2 ? 1000 : 20,
}));
const metrics: Metrics = {
  meters: 800,
  moving_meters: 800,
  moving_seconds: 40,
  stopped_seconds: 0,
  unknown_seconds: 60,
  elapsed_seconds: 100,
  moving_average_mps: 20,
  overall_average_mps: null,
  observed_max_mps: 20,
};
const provenance = {
  version: 1 as const,
  device_id: device,
  source_revision: 1,
  metadata_revision: 0,
  algorithm_version: "synthetic-1",
  timezone: "Europe/Warsaw" as const,
  from: "2026-10-05",
  to: "2026-10-05",
  context: {
    start_at: timestamp(-86400),
    end_at: timestamp(86400),
    max_points: 650000,
    max_days: 33,
  },
};
const journey = {
  ...metrics,
  journey_id: "40000000-0000-4000-8000-000000000001",
  start_at: timestamp(0),
  end_at: timestamp(100),
  point_count: points.length,
  stop_count: 0,
  complete: false,
  start_boundary: "recording_boundary" as const,
  end_boundary: "gps_gap" as const,
  start: { lat: 0, lon: 0, place: home },
  end: { lat: 0, lon: 0.01, place: destination },
  departure_hour: 10,
};
const visit = {
  visit_id: "50000000-0000-4000-8000-000000000001",
  start_at: timestamp(200),
  end_at: timestamp(800),
  duration_seconds: 600,
  overlap_seconds: 600,
  lat: 0,
  lon: 0.01,
  place: null,
};
export function analyticsFixture(): {
  summary: AnalyticsResponse;
  detail: JourneyDetailResponse;
} {
  return structuredClone({
    summary: {
      ...provenance,
      totals: metrics,
      journeys: [journey],
      days: [
        {
          ...metrics,
          period: "2026-10-05",
          journey_count: 1,
          complete_journey_count: 0,
          stop_count: 1,
        },
      ],
      weeks: [],
      months: [],
      visits: [visit],
      places: [home, destination],
      comparisons: [
        {
          from_place: home,
          to_place: destination,
          count: 3,
          complete_count: 2,
          mean_seconds: 100,
          mean_meters: 800,
          mean_stop_count: 1,
          median_seconds: 90,
          median_meters: 750,
          median_stop_count: 1,
          min_seconds: 80,
          max_seconds: 120,
          departures: [
            {
              hour: 10,
              count: 3,
              mean_seconds: 100,
              mean_meters: 800,
              median_seconds: 90,
              median_meters: 750,
            },
          ],
        },
      ],
    },
    detail: {
      ...provenance,
      journey,
      points,
      segments: [
        { run_id: "run-one", points: points.slice(0, 3) },
        { run_id: "run-two", points: points.slice(3) },
      ],
      timeline: [
        {
          start_at: timestamp(0),
          end_at: timestamp(20),
          state: "moving",
          meters: 400,
        },
        {
          start_at: timestamp(20),
          end_at: timestamp(80),
          state: "unknown",
          meters: 0,
        },
        {
          start_at: timestamp(80),
          end_at: timestamp(100),
          state: "moving",
          meters: 400,
        },
      ],
      speed_samples: points.map((p, i) => ({
        event_id: p.event_id,
        measured_at: p.measured_at,
        speed_mps: p.speed_mps,
        accepted: i !== 2 && i !== 4,
      })),
      visits: [],
    },
  });
}
