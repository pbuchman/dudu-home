import type { AnalyticsResponse as ContractAnalyticsResponse } from "../../contract/analytics.js";
import type { RecordedTimeDerivation } from "./types";

// Optional fields preserve compatibility with an older backend. Never infer these
// values from journey elapsed time, map samples or an absent response field.
type RecordedMetric = { recorded_seconds?: number | null; point_count?: number };
type CompatibleMetric<T> = Omit<T, "recorded_seconds" | "point_count"> &
  RecordedMetric;
export type AnalyticsResponse = Omit<
  ContractAnalyticsResponse,
  "totals" | "days" | "weeks" | "months" | "recorded_time_derivation"
> & {
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
