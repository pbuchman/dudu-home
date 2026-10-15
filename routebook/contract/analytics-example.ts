import type { AnalyticsResponse } from './analytics.js';
/** Invented equatorial samples only. */
export const analyticsExample = {
  "version": 1,
  "device_id": "00000000-0000-4000-8000-000000000999",
  "source_revision": 3,
  "metadata_revision": 2,
  "algorithm_version": "routebook-analytics-1",
  "recorded_time_derivation": {"algorithm_version":"routebook-recorded-time-1","gap_seconds":30},
  "timezone": "Europe/Warsaw",
  "from": "2026-10-05",
  "to": "2026-10-05",
  "context": {
    "start_at": "2026-10-03T22:00:00.000Z",
    "end_at": "2026-10-06T22:00:00.000Z",
    "max_days": 31,
    "max_points": 650000
  },
  "totals": {
    "recorded_seconds": 1305,
    "point_count": 262,
    "meters": 1050.7920567910803,
    "moving_meters": 1050.7920567910803,
    "moving_seconds": 105,
    "stopped_seconds": 0,
    "unknown_seconds": 0,
    "elapsed_seconds": 105,
    "moving_average_mps": 10.007543398010288,
    "overall_average_mps": 10.007543398010288,
    "observed_max_mps": 10
  },
  "journeys": [
    {
      "meters": 1050.7920567910803,
      "moving_meters": 1050.7920567910803,
      "moving_seconds": 105,
      "stopped_seconds": 0,
      "unknown_seconds": 0,
      "elapsed_seconds": 105,
      "moving_average_mps": 10.007543398010288,
      "overall_average_mps": 10.007543398010288,
      "observed_max_mps": 10,
      "journey_id": "00000000-0000-4000-8000-000000000121",
      "start_at": "2026-10-05T08:10:00.000Z",
      "end_at": "2026-10-05T08:11:45.000Z",
      "point_count": 22,
      "stop_count": 0,
      "complete": true,
      "start_boundary": "confirmed_stop",
      "end_boundary": "confirmed_stop",
      "start": {
        "lat": 0,
        "lon": 0,
        "place": {
          "place_id": "00000000-0000-4000-8000-000000000701",
          "name": "Synthetic A",
          "lat": 0,
          "lon": 0,
          "radius_m": 100
        }
      },
      "end": {
        "lat": 0,
        "lon": 0.009450000000000002,
        "place": {
          "place_id": "00000000-0000-4000-8000-000000000702",
          "name": "Synthetic B",
          "lat": 0,
          "lon": 0.009450000000000002,
          "radius_m": 100
        }
      },
      "departure_hour": 10
    }
  ],
  "days": [
    {
      "recorded_seconds": 1305,
      "point_count": 262,
      "meters": 1050.7920567910803,
      "moving_meters": 1050.7920567910803,
      "moving_seconds": 105,
      "stopped_seconds": 0,
      "unknown_seconds": 0,
      "elapsed_seconds": 105,
      "moving_average_mps": 10.007543398010288,
      "overall_average_mps": 10.007543398010288,
      "observed_max_mps": 10,
      "period": "2026-10-05",
      "journey_count": 1,
      "complete_journey_count": 1,
      "stop_count": 2
    }
  ],
  "weeks": [
    {
      "recorded_seconds": 1305,
      "point_count": 262,
      "meters": 1050.7920567910803,
      "moving_meters": 1050.7920567910803,
      "moving_seconds": 105,
      "stopped_seconds": 0,
      "unknown_seconds": 0,
      "elapsed_seconds": 105,
      "moving_average_mps": 10.007543398010288,
      "overall_average_mps": 10.007543398010288,
      "observed_max_mps": 10,
      "period": "2026-10-05",
      "journey_count": 1,
      "complete_journey_count": 1,
      "stop_count": 2
    }
  ],
  "months": [
    {
      "recorded_seconds": 1305,
      "point_count": 262,
      "meters": 1050.7920567910803,
      "moving_meters": 1050.7920567910803,
      "moving_seconds": 105,
      "stopped_seconds": 0,
      "unknown_seconds": 0,
      "elapsed_seconds": 105,
      "moving_average_mps": 10.007543398010288,
      "overall_average_mps": 10.007543398010288,
      "observed_max_mps": 10,
      "period": "2026-10",
      "journey_count": 1,
      "complete_journey_count": 1,
      "stop_count": 2
    }
  ],
  "visits": [
    {
      "visit_id": "00000000-0000-4000-8000-000000000001",
      "lat": 0,
      "lon": 0,
      "start_at": "2026-10-05T08:00:00.000Z",
      "end_at": "2026-10-05T08:10:00.000Z",
      "duration_seconds": 600,
      "place": {
        "place_id": "00000000-0000-4000-8000-000000000701",
        "name": "Synthetic A",
        "lat": 0,
        "lon": 0,
        "radius_m": 100
      },
      "overlap_seconds": 600
    },
    {
      "visit_id": "00000000-0000-4000-8000-000000000142",
      "lat": 0,
      "lon": 0.009450000000000002,
      "start_at": "2026-10-05T08:11:45.000Z",
      "end_at": "2026-10-05T08:21:45.000Z",
      "duration_seconds": 600,
      "place": {
        "place_id": "00000000-0000-4000-8000-000000000702",
        "name": "Synthetic B",
        "lat": 0,
        "lon": 0.009450000000000002,
        "radius_m": 100
      },
      "overlap_seconds": 600
    }
  ],
  "places": [
    {
      "place_id": "00000000-0000-4000-8000-000000000701",
      "name": "Synthetic A",
      "lat": 0,
      "lon": 0,
      "radius_m": 100
    },
    {
      "place_id": "00000000-0000-4000-8000-000000000702",
      "name": "Synthetic B",
      "lat": 0,
      "lon": 0.009450000000000002,
      "radius_m": 100
    }
  ],
  "comparisons": [
    {
      "from_place": {
        "place_id": "00000000-0000-4000-8000-000000000701",
        "name": "Synthetic A",
        "lat": 0,
        "lon": 0,
        "radius_m": 100
      },
      "to_place": {
        "place_id": "00000000-0000-4000-8000-000000000702",
        "name": "Synthetic B",
        "lat": 0,
        "lon": 0.009450000000000002,
        "radius_m": 100
      },
      "count": 1,
      "complete_count": 1,
      "mean_seconds": 105,
      "mean_meters": 1050.7920567910803,
      "mean_stop_count": 0,
      "median_seconds": 105,
      "median_meters": 1050.7920567910803,
      "median_stop_count": 0,
      "min_seconds": 105,
      "max_seconds": 105,
      "departures": [
        {
          "hour": 10,
          "count": 1,
          "mean_seconds": 105,
          "mean_meters": 1050.7920567910803,
          "median_seconds": 105,
          "median_meters": 1050.7920567910803
        }
      ]
    }
  ]
} satisfies AnalyticsResponse;
