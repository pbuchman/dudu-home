import type { RecordedMetric } from "./recorded-time";
import { recordedTime } from "./recorded-time";

export const validSeconds = (
  value: number | null | undefined,
): value is number =>
  typeof value === "number" && Number.isFinite(value) && value >= 0;

export function correctionLabel(value: Partial<RecordedMetric>): string {
  if (!validSeconds(value.in_car_seconds))
    return "Zapis GPS (surowy) · korekta niedostępna";
  if (
    ![
      value.excluded_tail_seconds,
      value.confirmed_excluded_seconds,
      value.estimated_excluded_seconds,
      value.provisional_excluded_seconds,
    ].every(validSeconds)
  )
    return "Czas po korekcie · szczegóły niedostępne";
  if ((value.provisional_excluded_seconds ?? 0) > 0)
    return "Czas szacowany · tymczasowy";
  if ((value.estimated_excluded_seconds ?? 0) > 0)
    return "Czas szacowany · korekta na podstawie GPS";
  if ((value.confirmed_excluded_seconds ?? 0) > 0)
    return "Korekta potwierdzona sygnałem zasilania";
  return "Na podstawie zapisu GPS · bez wykluczonej końcówki";
}

export function InCarValue({
  value,
  observed = true,
}: {
  value: RecordedMetric;
  observed?: boolean;
}) {
  return (
    <>
      {recordedTime(
        validSeconds(value.in_car_seconds)
          ? value.in_car_seconds
          : value.recorded_seconds,
        observed,
      )}
    </>
  );
}

export function CorrectionBreakdown({
  value,
  observed = true,
}: {
  value: RecordedMetric;
  observed?: boolean;
}) {
  return (
    <details className="correction-detail">
      <summary>Zapis GPS i korekta</summary>
      <dl>
        <div>
          <dt>Zapis GPS (surowy)</dt>
          <dd>{recordedTime(value.recorded_seconds, observed)}</dd>
        </div>
        <div>
          <dt>Wykluczona końcówka postoju</dt>
          <dd>{recordedTime(value.excluded_tail_seconds, observed)}</dd>
        </div>
        <div>
          <dt>Potwierdzone sygnałem zasilania</dt>
          <dd>{recordedTime(value.confirmed_excluded_seconds, observed)}</dd>
        </div>
        <div>
          <dt>Szacowane na podstawie GPS</dt>
          <dd>{recordedTime(value.estimated_excluded_seconds, observed)}</dd>
        </div>
        <div>
          <dt>W tym tymczasowe · otwarta końcówka</dt>
          <dd>{recordedTime(value.provisional_excluded_seconds, observed)}</dd>
        </div>
      </dl>
      <p>
        Szacowana korekta końcówki postoju może się zmienić po uzupełnieniu
        historii. Sygnał zasilania potwierdza tylko wskazaną część korekty.
        Zapis GPS nie potwierdza obecności w samochodzie. Ręczny licznik radia
        jest niezależny.
      </p>
    </details>
  );
}

export function InCarTime({
  value,
  observed = true,
}: {
  value: RecordedMetric;
  observed?: boolean;
}) {
  return (
    <div className="in-car-time">
      <dl className="recorded-metric">
        <dt>Czas w samochodzie</dt>
        <dd>
          <InCarValue value={value} observed={observed} />
        </dd>
        <dd className="recorded-explanation">{correctionLabel(value)}</dd>
      </dl>
      <CorrectionBreakdown value={value} observed={observed} />
    </div>
  );
}
