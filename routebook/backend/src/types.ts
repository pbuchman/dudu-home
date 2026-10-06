export interface Point {
  event_id: string; segment_id: string; measured_at: string;
  lat: number; lon: number; accuracy_m: number; speed_mps: number | null;
}
export interface StoredPoint extends Point { device_id: string; received_at: string }
export interface Envelope { version: 1; device_id: string; lane: 'latest' | 'backfill'; points: unknown[] }
export interface Result { index: number; event_id: string | null; status: 'accepted' | 'duplicate' | 'rejected'; code: null | 'invalid_point' | 'event_conflict' }
export interface Ack { version: 1; device_id: string; received_at: string; revision: number; results: Result[] }
export interface Snapshot { device_id: string; revision: number; points: StoredPoint[] }
export interface Derivation {
  gap_seconds: number; max_edge_speed_mps: number; stationary_speed_mps: number;
  min_edge_m: number; stop_radius_m: number; stop_seconds: number; earth_radius_m: number;
}
export const defaults: Derivation = Object.freeze({ gap_seconds: 30, max_edge_speed_mps: 80,
  stationary_speed_mps: 0.7, min_edge_m: 5, stop_radius_m: 25, stop_seconds: 180, earth_radius_m: 6371000 });
export class HttpError extends Error { constructor(public statusCode: number, public code: string) { super(code); } }
export interface CommitEvent { snapshot: Snapshot; affected_dates: string[] }
export interface Store {
  ingest(envelope: Envelope, receivedAt: string): Promise<{ ack: Ack; event: CommitEvent | null }>;
  snapshot(deviceId: string, bounds?: { start_at: string; end_at: string }): Promise<Snapshot>;
  latestSnapshot(deviceId: string): Promise<Snapshot>;
  health(): Promise<void>;
}
