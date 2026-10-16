import type {
  AnalyticsResponse as ContractAnalyticsResponse,
  CorrectionMetrics,
  CorrectionMetadata,
  Journey as ContractJourney,
  JourneyDetailResponse as ContractJourneyDetailResponse,
  DirectionalComparison,
} from "../../contract/analytics.js";
import type { RecordedTimeDerivation } from "./types";

// Optional fields preserve compatibility with an older backend. Never infer these
// values from journey elapsed time, map samples or an absent response field.
export type RecordedMetric = Partial<CorrectionMetrics> & {
  recorded_seconds?: number | null;
  point_count?: number;
};
type CompatibleMetric<T> = Omit<T, keyof RecordedMetric> & RecordedMetric;
export type Journey = CompatibleMetric<ContractJourney>;
export type JourneyDetailResponse = Omit<
  ContractJourneyDetailResponse,
  keyof CorrectionMetadata | "journey" | "correction_intervals"
> &
  Partial<CorrectionMetadata> & {
    journey: Journey;
    correction_intervals?: ContractJourneyDetailResponse["correction_intervals"];
  };
type OptionalFields<T, K extends keyof T> = Omit<T, K> & Partial<Pick<T, K>>;
type ComparisonCorrectionKeys =
  | keyof CorrectionMetadata
  | "mean_recorded_seconds"
  | "median_recorded_seconds"
  | "mean_in_car_seconds"
  | "median_in_car_seconds"
  | "mean_excluded_tail_seconds"
  | "median_excluded_tail_seconds"
  | "confirmed_excluded_seconds"
  | "estimated_excluded_seconds"
  | "provisional_excluded_seconds";
type CompatibleComparison = Omit<
  OptionalFields<DirectionalComparison, ComparisonCorrectionKeys>,
  "departures"
> & {
  departures: OptionalFields<
    DirectionalComparison["departures"][number],
    "mean_in_car_seconds" | "median_in_car_seconds"
  >[];
};
export type AnalyticsResponse = Omit<
  ContractAnalyticsResponse,
  | keyof CorrectionMetadata
  | "totals"
  | "days"
  | "weeks"
  | "months"
  | "recorded_time_derivation"
  | "journeys"
  | "comparisons"
> &
  Partial<CorrectionMetadata> & {
    journeys: Journey[];
    comparisons: CompatibleComparison[];
    recorded_time_derivation?: RecordedTimeDerivation;
    totals: CompatibleMetric<ContractAnalyticsResponse["totals"]>;
    days: CompatibleMetric<ContractAnalyticsResponse["days"][number]>[];
    weeks: CompatibleMetric<ContractAnalyticsResponse["weeks"][number]>[];
    months: CompatibleMetric<ContractAnalyticsResponse["months"][number]>[];
  };

export function recordedTime(
  seconds: number | null | undefined,
  observed = true,
): string {
  if (!observed) return "Brak danych";
  if (typeof seconds !== "number" || !Number.isFinite(seconds) || seconds < 0)
    return "Niedostępne";
  const rounded = Math.round(seconds);
  const hours = Math.floor(rounded / 3600);
  const minutes = Math.floor((rounded % 3600) / 60);
  const remainder = rounded % 60;
  if (hours)
    return `${hours} godz. ${minutes} min${remainder ? ` ${remainder} s` : ""}`;
  if (minutes) return `${minutes} min${remainder ? ` ${remainder} s` : ""}`;
  return `${remainder} s`;
}
