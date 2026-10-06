export type Point = {
  event_id: string;
  segment_id: string;
  device_id: string;
  measured_at: string;
  received_at: string;
  lat: number;
  lon: number;
  accuracy_m: number;
  speed_mps: number | null;
};
export type Derivation = {
  gap_seconds: number;
  max_edge_speed_mps: number;
  stationary_speed_mps: number;
  min_edge_m: number;
  stop_radius_m: number;
  stop_seconds: number;
  earth_radius_m: number;
};
export type Stop = {
  stop_id: string;
  lat: number;
  lon: number;
  start_at: string;
  end_at: string;
  duration_seconds: number;
  overlap_seconds: number;
};
export type Segment = {
  run_id: string;
  source_segment_id: string;
  points: Point[];
  edge_from: Point | null;
};
export type Day = {
  version: 1;
  device_id: string;
  revision: number;
  date: string;
  timezone: string;
  derivation: Derivation;
  meters: number;
  point_count: number;
  segments: Segment[];
  stops: Stop[];
};
export type Range = {
  version: 1;
  device_id: string;
  revision: number;
  timezone: string;
  from: string;
  to: string;
  derivation: Derivation;
  days: {
    date: string;
    meters: number;
    point_count: number;
    stop_count: number;
  }[];
  months: { month: string; meters: number }[];
  meters: number;
};
export type ClockState = "empty" | "future_clock" | "fresh" | "stale";
export type Latest = {
  version: 1;
  device_id: string;
  revision: number;
  server_time: string;
  point: Point | null;
  fresh: boolean;
  clock_state: ClockState;
};
export type LiveEvent = {
  version: 1;
  device_id: string;
  revision: number;
  latest: Latest;
  affected_dates: string[];
};
export type RuntimeConfig = {
  mode?: "api" | "synthetic";
  deviceId?: string;
  mapTilerKey?: string;
  mapTilerStyle?: string;
};
export interface Adapter {
  day(date: string, signal: AbortSignal): Promise<Day>;
  range(from: string, to: string, signal: AbortSignal): Promise<Range>;
  latest(signal: AbortSignal): Promise<Latest>;
  subscribe(
    event: (value: LiveEvent, kind: "snapshot" | "update") => void,
    status: (value: "connected" | "offline") => void,
  ): () => void;
}
