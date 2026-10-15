/** Private analytics v1. Units: metres, seconds and m/s; all coordinates synthetic in examples. */
export interface AnalyticsPoint {
  event_id:string; segment_id:string; measured_at:string; received_at:string;
  device_id:string; lat:number; lon:number; accuracy_m:number; speed_mps:number|null;
}
export interface NamedPlace {place_id:string;name:string;lat:number;lon:number;radius_m:number}
export interface PlacesResponse {version:1;device_id:string;metadata_revision:number;places:NamedPlace[]}
export interface PlaceWrite {expected_metadata_revision:number;name:string;lat:number;lon:number;radius_m?:number}
export interface Metrics {
  meters:number;moving_meters:number;moving_seconds:number;stopped_seconds:number;unknown_seconds:number;
  elapsed_seconds:number;moving_average_mps:number|null;overall_average_mps:number|null;
  observed_max_mps:number|null;
}
export interface JourneyEndpoint {lat:number;lon:number;place:NamedPlace|null}
export type BoundaryReason='confirmed_stop'|'gps_gap'|'recording_boundary'|'context_limit';
export interface Journey extends Metrics {
  journey_id:string;start_at:string;end_at:string;point_count:number;stop_count:number;
  complete:boolean;start_boundary:BoundaryReason;end_boundary:BoundaryReason;
  start:JourneyEndpoint;end:JourneyEndpoint;departure_hour:number;
}
export interface Visit {
  visit_id:string;start_at:string;end_at:string;duration_seconds:number;overlap_seconds:number;
  lat:number;lon:number;place:NamedPlace|null;
}
export interface RecordedTimeDerivation {algorithm_version:"routebook-recorded-time-1";gap_seconds:30}
export interface RecordedMetrics extends Metrics {recorded_seconds:number;point_count:number}
export interface PeriodSummary extends RecordedMetrics {period:string;journey_count:number;complete_journey_count:number;stop_count:number}
export interface DirectionalComparison {
  from_place:NamedPlace;to_place:NamedPlace;count:number;complete_count:number;
  mean_seconds:number;mean_meters:number;mean_stop_count:number;
  median_seconds:number;median_meters:number;median_stop_count:number;
  min_seconds:number;max_seconds:number;
  departures:{hour:number;count:number;mean_seconds:number;mean_meters:number;median_seconds:number;median_meters:number}[];
}
export interface AnalyticsProvenance {
  version:1;device_id:string;source_revision:number;metadata_revision:number;algorithm_version:string;
  timezone:'Europe/Warsaw';from:string;to:string;
  context:{start_at:string;end_at:string;max_points:number;max_days:number};
}
export interface AnalyticsResponse extends AnalyticsProvenance {
  recorded_time_derivation:RecordedTimeDerivation;
  totals:RecordedMetrics;journeys:Journey[];days:PeriodSummary[];weeks:PeriodSummary[];months:PeriodSummary[];
  visits:Visit[];places:NamedPlace[];comparisons:DirectionalComparison[];
}
export interface TimelineInterval {start_at:string;end_at:string;state:'moving'|'stopped'|'unknown';meters:number}
export interface SpeedSample {event_id:string;measured_at:string;speed_mps:number|null;accepted:boolean}
export interface JourneyDetailResponse extends AnalyticsProvenance {
  journey:Journey;points:AnalyticsPoint[];
  segments:{run_id:string;points:AnalyticsPoint[]}[];
  timeline:TimelineInterval[];speed_samples:SpeedSample[];visits:Visit[];
}
