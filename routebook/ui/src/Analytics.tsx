import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Metrics, NamedPlace, Visit } from "../../contract/analytics.js";
import type { RuntimeConfig } from "./types";
import { analyticsApi } from "./analytics-api";
import {
  duration,
  nearestObservation,
  periodBounds,
  replayStep,
  sameRevision,
  speed,
} from "./analytics-model";
import { km, localDate, time, type Geometry } from "./model";
import MapView from "./MapView";
import {
  recordedTime,
  type AnalyticsResponse,
  type Journey,
  type JourneyDetailResponse,
} from "./recorded-time";
import {
  InCarTime,
  InCarValue,
  CorrectionBreakdown,
  correctionLabel,
  validSeconds,
} from "./InCarTime";
import { useCorrectionRefresh } from "./use-correction-refresh";

const boundaries = {
  confirmed_stop: "potwierdzony postój",
  gps_gap: "luka GPS",
  recording_boundary: "granica zapisu",
  context_limit: "granica dostępnych obserwacji",
};
const endpoint = (value: Journey["start"]) =>
  value.place?.name ?? "Nienazwane miejsce";
const datetime = (value: string) => `${localDate(value)} · ${time(value)}`;
function MetricsView({
  value,
  observed = true,
}: {
  value: Metrics;
  observed?: boolean;
}) {
  const entries = [
    ["Dystans GPS", observed ? `${km(value.meters)} km` : "Brak danych"],
    ["W ruchu", observed ? duration(value.moving_seconds) : "Brak danych"],
    [
      "Postoje w przejazdach",
      observed ? duration(value.stopped_seconds) : "Brak danych",
    ],
    [
      "Czas bez rozstrzygnięcia",
      observed ? duration(value.unknown_seconds) : "Brak danych",
    ],
    ["Średnia w ruchu", speed(observed ? value.moving_average_mps : null)],
    [
      "Średnia całej podróży",
      speed(observed ? value.overall_average_mps : null),
    ],
    ["Maksimum obserwowane", speed(observed ? value.observed_max_mps : null)],
  ];
  return (
    <dl className="analytics-metrics">
      {entries.map(([label, text]) => (
        <div key={label}>
          <dt
            title={
              label === "Średnia w ruchu"
                ? "Dystans w ruchu / obserwowany czas ruchu"
                : undefined
            }
          >
            {label}
          </dt>
          <dd>{text}</dd>
        </div>
      ))}
    </dl>
  );
}
export default function Analytics({
  config,
  date,
  invalidation,
}: {
  config: RuntimeConfig;
  date: string;
  invalidation: string;
}) {
  const api = useMemo(() => analyticsApi(config.deviceId!), [config.deviceId]);
  const [period, setPeriod] = useState<"day" | "week" | "month">("day");
  const [anchor, setAnchor] = useState(date);
  const bounds = periodBounds(anchor, period);
  const [summary, setSummary] = useState<AnalyticsResponse>();
  const [selected, setSelected] = useState("");
  const [detail, setDetail] = useState<JourneyDetailResponse>();
  const [error, setError] = useState("");
  const [detailError, setDetailError] = useState("");
  const [refresh, setRefresh] = useState(0);
  const correctionRefresh = useCallback(() => setRefresh((n) => n + 1), []);
  useCorrectionRefresh(summary?.correction_next_refresh_at, correctionRefresh);
  const [loading, setLoading] = useState(false);
  const [draft, setDraft] = useState<PlaceDraft>();
  const [saving, setSaving] = useState(false);
  const [writeError, setWriteError] = useState("");
  const [removeId, setRemoveId] = useState("");
  const writeAbort = useRef<AbortController | undefined>(undefined);
  const mismatchRetries = useRef(0);
  useEffect(() => {
    mismatchRetries.current = 0;
  }, [invalidation, bounds.from, bounds.to]);
  useEffect(() => () => writeAbort.current?.abort(), []);
  useEffect(() => {
    const controller = new AbortController();
    setDetail(undefined);
    setSummary(undefined);
    setError("");
    setLoading(true);
    if (config.mode === "synthetic") {
      setLoading(false);
      return () => controller.abort();
    }
    void api
      .summary(bounds.from, bounds.to, controller.signal)
      .then((value) => {
        if (controller.signal.aborted) return;
        setSummary(value);
        setSelected((id) =>
          value.journeys.some((j) => j.journey_id === id)
            ? id
            : (value.journeys[0]?.journey_id ?? ""),
        );
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setError(e instanceof Error ? e.message : "Błąd pobierania analiz.");
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [api, bounds.from, bounds.to, config.mode, invalidation, refresh]);
  useEffect(() => {
    const controller = new AbortController();
    setDetail(undefined);
    setDetailError("");
    if (summary && selected)
      void api
        .journey(bounds.from, bounds.to, selected, controller.signal)
        .then((value) => {
          if (controller.signal.aborted) return;
          if (!sameRevision(value, summary)) {
            if (mismatchRetries.current++ < 2) {
              setRefresh((n) => n + 1);
              return;
            }
            setDetailError(
              "Historia została uzupełniona. Odśwież analizy, aby zsynchronizować podróż.",
            );
            return;
          }
          if (value.journey.journey_id !== selected)
            throw Error("Odpowiedź dotyczy innej podróży. Odśwież analizy.");
          setDetail(value);
        })
        .catch((e: unknown) => {
          if (!controller.signal.aborted)
            setDetailError(
              e instanceof Error ? e.message : "Błąd pobierania podróży.",
            );
        });
    return () => controller.abort();
  }, [api, bounds.from, bounds.to, summary, selected]);
  async function savePlace(event: React.FormEvent) {
    event.preventDefault();
    if (!summary || !draft || saving) return;
    const controller = new AbortController();
    writeAbort.current = controller;
    setSaving(true);
    setWriteError("");
    try {
      await api.write(
        draft.id,
        {
          expected_metadata_revision: summary.metadata_revision,
          name: draft.name.trim(),
          lat: Number(draft.lat),
          lon: Number(draft.lon),
          radius_m: Number(draft.radius),
        },
        controller.signal,
      );
      if (!controller.signal.aborted) {
        setDraft(undefined);
        setRefresh((n) => n + 1);
      }
    } catch (e) {
      if (!controller.signal.aborted) setWriteError((e as Error).message);
    } finally {
      if (!controller.signal.aborted) setSaving(false);
    }
  }
  async function removePlace(id: string) {
    if (!summary || saving) return;
    const controller = new AbortController();
    writeAbort.current = controller;
    setSaving(true);
    setWriteError("");
    try {
      await api.remove(id, summary.metadata_revision, controller.signal);
      if (!controller.signal.aborted) {
        setRemoveId("");
        setRefresh((n) => n + 1);
      }
    } catch (e) {
      if (!controller.signal.aborted) setWriteError((e as Error).message);
    } finally {
      if (!controller.signal.aborted) setSaving(false);
    }
  }
  return (
    <main className="analytics">
      <div className="analytics-heading">
        <div>
          <span className="eyebrow">Twoje obserwacje</span>
          <h1>Dziennik podróży</h1>
          <p>Przejazdy, miejsca i rytm dnia z zapisanych pomiarów GPS.</p>
        </div>
        <div className="analytics-period">
          <div className="tabs" aria-label="Okres analiz">
            {(["day", "week", "month"] as const).map((p, i) => (
              <button
                key={p}
                aria-pressed={period === p}
                onClick={() => setPeriod(p)}
              >
                {["Dzień", "Tydzień", "Miesiąc"][i]}
              </button>
            ))}
          </div>
          <label>
            Data okresu
            <input
              type="date"
              value={anchor}
              onChange={(e) => {
                if (e.target.value) setAnchor(e.target.value);
              }}
            />
          </label>
          <button onClick={() => setRefresh((n) => n + 1)}>
            Odśwież analizy
          </button>
        </div>
      </div>
      <p className="period-caption">
        {bounds.from === bounds.to
          ? bounds.from
          : `${bounds.from} – ${bounds.to}`}{" "}
        · Europe/Warsaw
      </p>
      {loading ? (
        <p className="notice" role="status">
          Pobieranie obserwacji…
        </p>
      ) : null}
      {config.mode === "synthetic" ? (
        <p className="notice">
          Analizy wymagają połączonego archiwum. Scenariusze demonstracyjne
          dotyczą widoków Dzień, Na żywo i Zakres.
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="notice warning">
          {error}
        </p>
      ) : null}
      {summary ? (
        <>
          <section
            className="analytics-card period-card"
            aria-label="Podsumowanie okresu"
          >
            <div className="section-title">
              <h2>
                Wybrany{" "}
                {period === "day"
                  ? "dzień"
                  : period === "week"
                    ? "tydzień"
                    : "miesiąc"}
              </h2>
              <span>
                {summary.journeys.length} podróży ·{" "}
                {summary.journeys.filter((j) => j.complete).length} kompletnych
              </span>
            </div>
            <InCarTime
              value={summary.totals}
              observed={summary.totals.point_count !== 0}
            />
            <MetricsView
              value={summary.totals}
              observed={summary.journeys.length > 0}
            />
            <p className="muted">
              {validSeconds(summary.totals.in_car_seconds)
                ? "Czas w samochodzie pomija wykluczoną końcówkę postoju; szczegóły pokazują surowy zapis i podstawę korekty. "
                : "Korekta czasu jest niedostępna. Surowy czas GPS obejmuje także końcowy postój przy ciągłym zapisie. "}
              Pozostałe czasy i dystans dotyczą przejazdów w wybranym okresie.
              Parking między przejazdami (≥10 min) jest pominięty; pełne wizyty
              są poniżej. Podróż na granicy dat ma pełne dane w dzienniku.
              Średnia całej podróży jest dostępna wyłącznie przy kompletnym
              zapisie.
            </p>
            <RecordedPeriods summary={summary} />
          </section>
          <div className="journey-workspace">
            <section className="analytics-card journey-list">
              <div className="section-title">
                <h2>Podróże</h2>
                <span>{summary.journeys.length}</span>
              </div>
              {summary.journeys.length ? (
                summary.journeys.map((j) => (
                  <button
                    className={`journey-row ${selected === j.journey_id ? "selected" : ""}`}
                    key={j.journey_id}
                    aria-pressed={selected === j.journey_id}
                    onClick={() => setSelected(j.journey_id)}
                  >
                    <span className="journey-time">
                      {localDate(j.start_at)} · {time(j.start_at)} –{" "}
                      {time(j.end_at)}
                    </span>
                    <strong>
                      {endpoint(j.start)} <span aria-hidden="true">→</span>{" "}
                      {endpoint(j.end)}
                    </strong>
                    <span>
                      {km(j.meters)} km ·{" "}
                      {validSeconds(j.in_car_seconds) ||
                      validSeconds(j.recorded_seconds) ? (
                        <InCarValue value={j} />
                      ) : (
                        duration(j.elapsed_seconds)
                      )}
                      <small className="recorded-explanation">
                        {validSeconds(j.in_car_seconds) ||
                        validSeconds(j.recorded_seconds)
                          ? correctionLabel(j)
                          : "Czas podróży (z lukami) · korekta niedostępna"}
                      </small>
                    </span>
                    <span
                      className={`quality-badge ${j.complete ? "complete" : "partial"}`}
                    >
                      {j.complete ? "Kompletny zapis" : "Niepełny zapis"}
                    </span>
                  </button>
                ))
              ) : (
                <p className="empty-note">
                  Brak podróży w tym okresie. Brak obserwacji nie oznacza
                  postoju.
                </p>
              )}
            </section>
            <section
              className="analytics-card journey-detail"
              aria-label="Wybrana podróż"
            >
              {detailError ? (
                <p className="notice warning" role="alert">
                  {detailError}
                </p>
              ) : detail && detail.journey.journey_id === selected ? (
                <JourneyPanel
                  detail={detail}
                  config={config}
                  onPlace={(lat, lon) => {
                    setDraft(placeDraft(lat, lon));
                    setWriteError("");
                  }}
                />
              ) : (
                <p className="empty-note" role="status">
                  {selected
                    ? "Pobieranie podróży…"
                    : "Wybierz podróż, aby zobaczyć trasę i prędkość."}
                </p>
              )}
            </section>
          </div>
          <section className="analytics-card">
            <div className="section-title">
              <h2>Obserwowane wizyty</h2>
              <span>{summary.visits.length}</span>
            </div>
            <p className="muted">
              Wyłącznie obserwowane postoje ≥3 min. Luka GPS nie jest wizytą.
            </p>
            {summary.visits.length ? (
              <div className="visits">
                {summary.visits.map((v) => (
                  <VisitRow
                    key={v.visit_id}
                    visit={v}
                    onPlace={() => {
                      setDraft(
                        v.place ? fromPlace(v.place) : placeDraft(v.lat, v.lon),
                      );
                      setWriteError("");
                    }}
                  />
                ))}
              </div>
            ) : (
              <p className="empty-note">
                Brak obserwowanych wizyt w tym okresie.
              </p>
            )}
          </section>
          <section className="analytics-card">
            <div className="section-title">
              <h2>Powtarzające się kierunki</h2>
              <span>A → B</span>
            </div>
            <p className="muted">
              Te same nazwane miejsca początku i końca nie oznaczają tych samych
              dróg. Mediany obejmują zaobserwowane podróże; liczba kompletnych
              zapisów pokazuje jakość próbki.
            </p>
            {summary.comparisons.length ? (
              <div className="comparison-grid">
                {summary.comparisons.map((c) => (
                  <article
                    className="comparison"
                    key={`${c.from_place.place_id}:${c.to_place.place_id}`}
                  >
                    <h3>
                      {c.from_place.name} → {c.to_place.name}
                    </h3>
                    <p>
                      {c.count} podróży · {c.complete_count} kompletnych
                    </p>
                    <dl>
                      <div>
                        <dt>Mediana czasu w samochodzie</dt>
                        <dd>{recordedTime(c.median_in_car_seconds)}</dd>
                      </div>
                      <div>
                        <dt>Średni czas w samochodzie</dt>
                        <dd>{recordedTime(c.mean_in_car_seconds)}</dd>
                      </div>
                      <div>
                        <dt>Mediana wykluczonej końcówki</dt>
                        <dd>{recordedTime(c.median_excluded_tail_seconds)}</dd>
                      </div>
                      <div>
                        <dt>Mediana zapisu GPS (surowego)</dt>
                        <dd>{recordedTime(c.median_recorded_seconds)}</dd>
                      </div>
                      <div>
                        <dt>Średni zapis GPS (surowy)</dt>
                        <dd>{recordedTime(c.mean_recorded_seconds)}</dd>
                      </div>
                      <div>
                        <dt>Średnia wykluczona końcówka</dt>
                        <dd>{recordedTime(c.mean_excluded_tail_seconds)}</dd>
                      </div>
                      <div>
                        <dt>Mediana czasu podróży (z lukami)</dt>
                        <dd>{duration(c.median_seconds)}</dd>
                      </div>
                      <div>
                        <dt>Mediana dystansu</dt>
                        <dd>{km(c.median_meters)} km</dd>
                      </div>
                      <div>
                        <dt>Mediana postojów</dt>
                        <dd>
                          {new Intl.NumberFormat("pl-PL", {
                            maximumFractionDigits: 1,
                          }).format(c.median_stop_count)}
                        </dd>
                      </div>
                    </dl>
                    <p className="recorded-explanation">
                      {correctionLabel({
                        in_car_seconds: c.median_in_car_seconds,
                        excluded_tail_seconds: c.median_excluded_tail_seconds,
                        confirmed_excluded_seconds:
                          c.confirmed_excluded_seconds,
                        estimated_excluded_seconds:
                          c.estimated_excluded_seconds,
                        provisional_excluded_seconds:
                          c.provisional_excluded_seconds,
                      })}
                    </p>
                    <p className="muted">
                      Wykluczenie w próbce: potwierdzone sygnałem zasilania{" "}
                      {recordedTime(c.confirmed_excluded_seconds)} · szacowane
                      GPS {recordedTime(c.estimated_excluded_seconds)} · w tym
                      tymczasowe {recordedTime(c.provisional_excluded_seconds)}.
                      Czas podróży przed korektą obejmuje również luki
                      obserwacji.
                    </p>
                    <details>
                      <summary>Godzina wyjazdu · liczebność próbki</summary>
                      <div className="table-scroll">
                        <table>
                          <thead>
                            <tr>
                              <th>Godzina</th>
                              <th>Podróże</th>
                              <th>Mediana czasu w samochodzie</th>
                              <th>Średni czas w samochodzie</th>
                              <th>Czas podróży przed korektą</th>
                              <th>Mediana km</th>
                            </tr>
                          </thead>
                          <tbody>
                            {c.departures.map((d) => (
                              <tr key={d.hour}>
                                <td>{String(d.hour).padStart(2, "0")}:00</td>
                                <td>{d.count}</td>
                                <td>{recordedTime(d.median_in_car_seconds)}</td>
                                <td>{recordedTime(d.mean_in_car_seconds)}</td>
                                <td>{duration(d.median_seconds)}</td>
                                <td>{km(d.median_meters)}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </details>
                  </article>
                ))}
              </div>
            ) : (
              <p className="empty-note">
                Brak porównań. Nadaj nazwy miejscom początku i końca, aby
                grupować kierunki.
              </p>
            )}
          </section>
          <section className="analytics-card" id="named-places">
            <div className="section-title">
              <h2>Twoje miejsca</h2>
              <button
                onClick={() => {
                  setDraft(placeDraft());
                  setWriteError("");
                }}
              >
                Dodaj miejsce
              </button>
            </div>
            <p className="muted">
              Nazwy i promienie są prywatne. Zmiana miejsca przelicza wizyty i
              kierunki, zachowując oryginalne pomiary.
            </p>
            {writeError ? (
              <p className="notice warning" role="alert">
                {writeError}
              </p>
            ) : null}
            {draft ? (
              <form className="place-form" onSubmit={savePlace}>
                <h3>{draft.id ? "Edytuj miejsce" : "Nowe miejsce"}</h3>
                <label>
                  Nazwa
                  <input
                    autoFocus
                    required
                    maxLength={80}
                    value={draft.name}
                    onChange={(e) =>
                      setDraft({ ...draft, name: e.target.value })
                    }
                  />
                </label>
                <label>
                  Szerokość geograficzna
                  <input
                    required
                    type="number"
                    min={-90}
                    max={90}
                    step="any"
                    value={draft.lat}
                    onChange={(e) =>
                      setDraft({ ...draft, lat: e.target.value })
                    }
                  />
                </label>
                <label>
                  Długość geograficzna
                  <input
                    required
                    type="number"
                    min={-180}
                    max={180}
                    step="any"
                    value={draft.lon}
                    onChange={(e) =>
                      setDraft({ ...draft, lon: e.target.value })
                    }
                  />
                </label>
                <label>
                  Promień (m)
                  <input
                    required
                    type="number"
                    min={25}
                    max={1000}
                    value={draft.radius}
                    onChange={(e) =>
                      setDraft({ ...draft, radius: e.target.value })
                    }
                  />
                </label>
                <div className="form-actions">
                  <button
                    className="primary"
                    disabled={saving || !draft.name.trim()}
                  >
                    {saving ? "Zapisywanie…" : "Zapisz miejsce"}
                  </button>
                  <button
                    type="button"
                    disabled={saving}
                    onClick={() => setDraft(undefined)}
                  >
                    Anuluj
                  </button>
                </div>
              </form>
            ) : null}
            {summary.places.length ? (
              <ul className="places-list">
                {summary.places.map((p) => (
                  <li key={p.place_id}>
                    <div>
                      <strong>{p.name}</strong>
                      <span>Promień {p.radius_m} m</span>
                    </div>
                    <div className="place-actions">
                      <button
                        disabled={saving}
                        onClick={() => {
                          setDraft(fromPlace(p));
                          setWriteError("");
                        }}
                      >
                        Edytuj<span className="sr-only"> {p.name}</span>
                      </button>
                      {removeId === p.place_id ? (
                        <>
                          <button
                            disabled={saving}
                            onClick={() => void removePlace(p.place_id)}
                          >
                            Potwierdź usunięcie
                          </button>
                          <button onClick={() => setRemoveId("")}>
                            Zachowaj
                          </button>
                        </>
                      ) : (
                        <button
                          disabled={saving}
                          onClick={() => setRemoveId(p.place_id)}
                        >
                          Usuń<span className="sr-only"> {p.name}</span>
                        </button>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="empty-note">
                Brak nazwanych miejsc. Możesz dodać je z wizyty albo
                zaznaczonego pomiaru.
              </p>
            )}
          </section>
          <p className="analytics-footnote">
            Historia odzwierciedla dostępne obserwacje. Późniejsze uzupełnienie
            zapisu może zmienić podróże i statystyki.
          </p>
        </>
      ) : null}
    </main>
  );
}
function RecordedPeriods({ summary }: { summary: AnalyticsResponse }) {
  return (
    <details className="recorded-periods">
      <summary>Czas w samochodzie według dni, tygodni i miesięcy</summary>
      {(
        [
          ["Dni", summary.days],
          ["Tygodnie od poniedziałku", summary.weeks],
          ["Miesiące", summary.months],
        ] as const
      ).map(([label, rows]) => (
        <div
          className="table-scroll"
          key={label}
          role="region"
          aria-label={label}
          tabIndex={0}
        >
          <table>
            <caption>{label} · na podstawie zapisu GPS</caption>
            <thead>
              <tr>
                <th scope="col">Okres</th>
                <th scope="col">Czas w samochodzie</th>
              </tr>
            </thead>
            <tbody>
              {rows.length ? (
                rows.map((row) => (
                  <tr key={row.period}>
                    <th scope="row">{row.period}</th>
                    <td>
                      <InCarValue
                        value={row}
                        observed={row.point_count !== 0}
                      />
                      <small className="recorded-explanation">
                        {correctionLabel(row)}
                      </small>
                      <CorrectionBreakdown
                        value={row}
                        observed={row.point_count !== 0}
                      />
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={2}>Brak danych</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      ))}
    </details>
  );
}
type PlaceDraft = {
  id?: string;
  name: string;
  lat: string;
  lon: string;
  radius: string;
};
const placeDraft = (lat?: number, lon?: number): PlaceDraft => ({
  name: "",
  lat: lat == null ? "" : String(lat),
  lon: lon == null ? "" : String(lon),
  radius: "100",
});
const fromPlace = (p: NamedPlace): PlaceDraft => ({
  id: p.place_id,
  name: p.name,
  lat: String(p.lat),
  lon: String(p.lon),
  radius: String(p.radius_m),
});
function VisitRow({
  visit: v,
  onPlace,
}: {
  visit: Visit;
  onPlace: () => void;
}) {
  return (
    <article className="visit-row">
      <span className="visit-dot" aria-hidden="true" />
      <div>
        <strong>{v.place?.name ?? "Nienazwane miejsce"}</strong>
        <span>
          {datetime(v.start_at)} – {datetime(v.end_at)}
        </span>
      </div>
      <div>
        <strong>{duration(v.duration_seconds)}</strong>
        <span>{duration(v.overlap_seconds)} w okresie</span>
      </div>
      <button onClick={onPlace}>
        {v.place ? "Edytuj miejsce" : "Nazwij miejsce"}
      </button>
    </article>
  );
}
function JourneyPanel({
  detail,
  config,
  onPlace,
}: {
  detail: JourneyDetailResponse;
  config: RuntimeConfig;
  onPlace: (lat: number, lon: number) => void;
}) {
  const [cursor, setCursor] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [tempo, setTempo] = useState(20);
  const [skip, setSkip] = useState("");
  const playbackClock = useRef(0);
  const cursorRef = useRef(0);
  const j = detail.journey;
  const point = detail.points[cursor];
  const samplesById = useMemo(
    () => new Map(detail.speed_samples.map((s) => [s.event_id, s])),
    [detail],
  );
  const selectedSpeed = samplesById.get(point?.event_id);
  const parts = useMemo<Record<string, Geometry>>(
    () => ({
      journey: {
        segments: detail.segments.map((s) => ({
          ...s,
          source_segment_id: s.points[0]?.segment_id ?? s.run_id,
          edge_from: null,
        })),
        stops: detail.visits.map((v) => ({ ...v, stop_id: v.visit_id })),
        gaps: detail.timeline
          .filter((t) => t.state === "unknown")
          .map((t) => ({ from: t.start_at, to: t.end_at })),
        points: detail.points.length,
      },
    }),
    [detail],
  );
  const runs = useMemo(
    () =>
      new Map(
        detail.segments.flatMap((s) =>
          s.points.map((p) => [p.event_id, s.run_id] as const),
        ),
      ),
    [detail],
  );
  useEffect(() => {
    setCursor(0);
    cursorRef.current = 0;
    setPlaying(false);
    setSkip("");
    playbackClock.current = Date.parse(
      detail.points[0]?.measured_at ?? j.start_at,
    );
  }, [detail, j.start_at]);
  useEffect(() => {
    if (!playing || !detail.points.length) return;
    let previous = performance.now();
    const id = setInterval(() => {
      const now = performance.now();
      playbackClock.current += (now - previous) * tempo;
      previous = now;
      const next = replayStep(
        detail,
        cursorRef.current,
        playbackClock.current,
        runs,
      );
      playbackClock.current = next.clock;
      cursorRef.current = next.cursor;
      setCursor(next.cursor);
      if (next.skipped > 0)
        setSkip(
          `Pominięto przerwę obserwacji: ${duration(next.skipped)}. Bez odtwarzania brakującej trasy.`,
        );
      if (next.cursor >= detail.points.length - 1) setPlaying(false);
    }, 100);
    return () => clearInterval(id);
  }, [playing, tempo, detail, runs]);
  function choose(index: number) {
    const bounded = Math.max(0, Math.min(detail.points.length - 1, index));
    setCursor(bounded);
    cursorRef.current = bounded;
    setPlaying(false);
    setSkip("");
    playbackClock.current = Date.parse(
      detail.points[bounded]?.measured_at ?? j.start_at,
    );
  }
  function play() {
    if (cursor >= detail.points.length - 1) {
      setCursor(0);
      cursorRef.current = 0;
      playbackClock.current = Date.parse(detail.points[0].measured_at);
    }
    setPlaying((value) => !value);
  }
  return (
    <>
      <div className="section-title">
        <h2>
          {endpoint(j.start)} → {endpoint(j.end)}
        </h2>
        <span
          className={`quality-badge ${j.complete ? "complete" : "partial"}`}
        >
          {j.complete ? "Kompletny zapis" : "Niepełny zapis"}
        </span>
      </div>
      <p className="journey-observations">
        Początek obserwacji: {datetime(j.start_at)}
        <br />
        Koniec obserwacji: {datetime(j.end_at)}
      </p>
      <p className="muted">
        Granice: {boundaries[j.start_boundary]} → {boundaries[j.end_boundary]}.{" "}
        {j.point_count} pomiarów · {j.stop_count} obserwowanych postojów.
      </p>
      <InCarTime value={j} observed={j.point_count !== 0} />
      <p className="muted">
        Pełny czas podróży przed korektą: {duration(j.elapsed_seconds)}. Luki
        obserwacji pozostają nierozstrzygnięte.
      </p>
      {detail.correction_intervals?.length ? (
        <details className="correction-detail">
          <summary>Przedziały wykluczenia</summary>
          <ul>
            {detail.correction_intervals.map((interval, i) => (
              <li key={`${interval.start_at}:${i}`}>
                {datetime(interval.start_at)} – {datetime(interval.end_at)} ·{" "}
                {interval.reason === "confirmed_power_off"
                  ? "potwierdzone sygnałem zasilania"
                  : interval.reason === "provisional_archive_tail"
                    ? "szacowany postój końcowy · tymczasowy"
                    : "szacowany postój końcowy na podstawie GPS"}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
      <MetricsView value={j} />
      <div className="journey-map">
        <MapView
          config={config}
          parts={parts}
          latest={null}
          historical={point ?? null}
          latestState="stale"
          selectionKey={j.journey_id}
          onObservation={(lon, lat) =>
            choose(nearestObservation(detail.points, lon, lat))
          }
        />
        <span className="historical-label">
          <i /> Pozycja historyczna ·{" "}
          {point ? time(point.measured_at) : "Brak pomiaru"}
        </span>
      </div>
      {detail.points.length ? (
        <>
          <SpeedChart detail={detail} cursor={cursor} onCursor={choose} />
          <div className="replay-controls">
            <button
              className="primary"
              onClick={play}
              aria-label={playing ? "Wstrzymaj odtwarzanie" : "Odtwórz podróż"}
            >
              {playing ? "Ⅱ Wstrzymaj" : "▶ Odtwórz"}
            </button>
            <label>
              Tempo
              <select
                aria-label="Tempo"
                value={tempo}
                onChange={(e) => setTempo(Number(e.target.value))}
              >
                {[1, 5, 20, 60, 120].map((t) => (
                  <option key={t} value={t}>
                    {t}×
                  </option>
                ))}
              </select>
            </label>
            <span>
              Pomiar {cursor + 1}/{detail.points.length}
            </span>
          </div>
          <label className="replay-slider">
            Pozycja w podróży
            <input
              aria-label="Pozycja w podróży"
              type="range"
              min={0}
              max={detail.points.length - 1}
              step={1}
              value={cursor}
              onChange={(e) => choose(Number(e.target.value))}
            />
          </label>
          <p className="cursor-readout">
            <strong>
              {point ? datetime(point.measured_at) : "Brak pomiaru"}
            </strong>
            <span>
              Prędkość pomiaru:{" "}
              {speed(selectedSpeed?.accepted ? selectedSpeed.speed_mps : null)}
              {selectedSpeed?.speed_mps != null && !selectedSpeed.accepted
                ? " · pomiar odrzucony jakościowo"
                : ""}
            </span>
            <button onClick={() => onPlace(point.lat, point.lon)}>
              Nazwij zaznaczone miejsce
            </button>
          </p>
          <p className="replay-note" aria-live="polite">
            {skip ||
              "Odtwarzanie przechodzi po zapisanych pomiarach. Przerwy są pomijane, bez interpolacji. Suwak i strzałki klawiatury wybierają pomiar."}
          </p>
        </>
      ) : null}
    </>
  );
}
function SpeedChart({
  detail,
  cursor,
  onCursor,
}: {
  detail: JourneyDetailResponse;
  cursor: number;
  onCursor: (value: number) => void;
}) {
  const svg = useRef<SVGSVGElement>(null);
  const { points, speed_samples: samples } = detail;
  const start = Date.parse(points[0].measured_at),
    end = Date.parse(points.at(-1)!.measured_at);
  const scaleX = (t: string) =>
    46 + ((Date.parse(t) - start) / Math.max(1, end - start)) * 900;
  const { max, paths } = useMemo(() => {
    let max = 10;
    for (const sample of samples)
      if (sample.accepted && sample.speed_mps != null)
        max = Math.max(max, sample.speed_mps * 3.6);
    const scaleY = (s: number) => 150 - ((s * 3.6) / max) * 120;
    const sampleMap = new Map(samples.map((s) => [s.event_id, s]));
    const paths = detail.segments.flatMap((segment) => {
      const paths: string[] = [];
      let path = "";
      for (const p of segment.points) {
        const sample = sampleMap.get(p.event_id);
        if (sample?.accepted && sample.speed_mps != null)
          path += `${path ? "L" : "M"}${scaleX(p.measured_at).toFixed(1)},${scaleY(sample.speed_mps).toFixed(1)} `;
        else if (path) {
          paths.push(path);
          path = "";
        }
      }
      if (path) paths.push(path);
      return paths;
    });
    return { max, paths };
  }, [detail]);
  function seek(clientX: number) {
    const rect = svg.current!.getBoundingClientRect();
    const clock =
      start +
      Math.max(
        0,
        Math.min(1, (((clientX - rect.left) / rect.width) * 980 - 46) / 900),
      ) *
        (end - start);
    let nearest = 0,
      distance = Infinity;
    points.forEach((p, i) => {
      const d = Math.abs(Date.parse(p.measured_at) - clock);
      if (d < distance) {
        nearest = i;
        distance = d;
      }
    });
    onCursor(nearest);
  }
  return (
    <div className="speed-chart">
      <div className="section-title">
        <h3>Prędkość w czasie</h3>
        <span>km/h · pomiary po filtrze jakości</span>
      </div>
      <svg
        ref={svg}
        viewBox="0 0 980 190"
        role="slider"
        aria-label="Kursor wykresu prędkości"
        aria-valuemin={1}
        aria-valuemax={points.length}
        aria-valuenow={cursor + 1}
        aria-valuetext={datetime(points[cursor].measured_at)}
        tabIndex={0}
        onKeyDown={(e) => {
          if (["ArrowLeft", "ArrowRight", "Home", "End"].includes(e.key)) {
            e.preventDefault();
            onCursor(
              e.key === "Home"
                ? 0
                : e.key === "End"
                  ? points.length - 1
                  : Math.max(
                      0,
                      Math.min(
                        points.length - 1,
                        cursor + (e.key === "ArrowRight" ? 1 : -1),
                      ),
                    ),
            );
          }
        }}
        onPointerDown={(e) => {
          e.currentTarget.setPointerCapture(e.pointerId);
          seek(e.clientX);
        }}
        onPointerMove={(e) => {
          if (e.buttons === 1) seek(e.clientX);
        }}
      >
        <line x1="46" x2="946" y1="150" y2="150" className="chart-grid" />
        <line x1="46" x2="946" y1="30" y2="30" className="chart-grid" />
        <text x="3" y="34">
          {Math.ceil(max)}
        </text>
        <text x="23" y="154">
          0
        </text>
        {detail.timeline
          .filter((t) => t.state === "unknown")
          .map((t, i) => (
            <rect
              key={i}
              x={scaleX(t.start_at)}
              y="25"
              width={Math.max(2, scaleX(t.end_at) - scaleX(t.start_at))}
              height="125"
              className="chart-gap"
            >
              <title>
                Brak rozstrzygnięcia:{" "}
                {duration(
                  (Date.parse(t.end_at) - Date.parse(t.start_at)) / 1000,
                )}
              </title>
            </rect>
          ))}
        {paths.map((path, i) => (
          <path key={i} d={path} className="speed-path" />
        ))}
        <line
          x1={scaleX(points[cursor].measured_at)}
          x2={scaleX(points[cursor].measured_at)}
          y1="20"
          y2="155"
          className="chart-cursor"
        />
        <text x="46" y="180">
          {time(points[0].measured_at)}
        </text>
        <text x="946" y="180" textAnchor="end">
          {time(points.at(-1)!.measured_at)}
        </text>
      </svg>
      <p className="muted">
        Szare pasma: nieznany przebieg. Brak lub odrzucona prędkość przerywa
        linię. Wykres i mapa wskazują ten sam pomiar.
      </p>
    </div>
  );
}
