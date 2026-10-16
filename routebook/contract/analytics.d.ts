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
export interface Journey extends Metrics, CorrectionMetrics {
  recorded_seconds:number;
  journey_id:string;start_at:string;end_at:string;point_count:number;stop_count:number;
  complete:boolean;start_boundary:BoundaryReason;end_boundary:BoundaryReason;
  start:JourneyEndpoint;end:JourneyEndpoint;departure_hour:number;
}
export interface Visit {
  visit_id:string;start_at:string;end_at:string;duration_seconds:number;overlap_seconds:number;
  lat:number;lon:number;place:NamedPlace|null;
}
export interface RecordedTimeDerivation {algorithm_version:"routebook-recorded-time-1";gap_seconds:30}
export interface RecordedMetrics extends Metrics, CorrectionMetrics {recorded_seconds:number;point_count:number}
export interface PeriodSummary extends RecordedMetrics {period:string;journey_count:number;complete_journey_count:number;stop_count:number}
export interface DirectionalComparison extends CorrectionMetadata {
  mean_recorded_seconds:number;median_recorded_seconds:number;
  mean_in_car_seconds:number;median_in_car_seconds:number;
  mean_excluded_tail_seconds:number;median_excluded_tail_seconds:number;
  confirmed_excluded_seconds:number;estimated_excluded_seconds:number;provisional_excluded_seconds:number;
  from_place:NamedPlace;to_place:NamedPlace;count:number;complete_count:number;
  mean_seconds:number;mean_meters:number;mean_stop_count:number;
  median_seconds:number;median_meters:number;median_stop_count:number;
  min_seconds:number;max_seconds:number;
  departures:{hour:number;count:number;mean_in_car_seconds:number;median_in_car_seconds:number;mean_seconds:number;mean_meters:number;median_seconds:number;median_meters:number}[];
}
export interface AnalyticsProvenance extends CorrectionMetadata {
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
  journey:Journey;correction_intervals:CorrectionInterval[];points:AnalyticsPoint[];
  segments:{run_id:string;points:AnalyticsPoint[]}[];
  timeline:TimelineInterval[];speed_samples:SpeedSample[];visits:Visit[];
}

/** Raw GPS time is preserved; provisional seconds are a subset of estimated seconds. */
export interface CorrectionMetadata {
  correction_algorithm_version:'routebook-in-car-1';
  correction_computed_at:string;
  correction_next_refresh_at:string|null;
}
export interface CorrectionMetrics extends CorrectionMetadata {
  in_car_seconds:number;excluded_tail_seconds:number;
  confirmed_excluded_seconds:number;estimated_excluded_seconds:number;provisional_excluded_seconds:number;
}
export interface CorrectionInterval {
  start_at:string;end_at:string;
  reason:'confirmed_power_off'|'estimated_archive_tail'|'provisional_archive_tail';
}
export interface RadioObservation {
  event_id:string;observer_epoch:string;sequence:number;observed_at:string;
  elapsed_realtime_ms:number;boot_id:string;kind:'snapshot'|'transition'|'observer_lost';
  acc_state:number|null;module1_state:number|null;interpretation_profile:string;firmware_id:string;
}
export interface RadioObservationEnvelope {version:2;device_id:string;lane:'latest'|'backfill';events:RadioObservation[]}
