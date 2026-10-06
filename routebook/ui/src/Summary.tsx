import type { Day, Range } from "./types";
import type { Geometry } from "./model";
import { km, time, partialMonth, observationGaps } from "./model";
export function Summary({
  day,
  range,
  parts,
  loading,
}: {
  day?: Day;
  range?: Range;
  parts: Record<string, Geometry>;
  loading: boolean;
}) {
  const gaps = observationGaps(parts);
  return (
    <aside className="summary" aria-label="Podsumowanie historii">
      <h1>{range ? "Wybrany zakres" : "Twój dzień"}</h1>
      <div className="metrics">
        <div>
          <strong>
            {km(range?.meters ?? day?.meters ?? 0)} <small>km</small>
          </strong>
          <span>GPS</span>
        </div>
        <div>
          <strong>
            {range
              ? range.days.reduce((n, d) => n + d.point_count, 0)
              : (day?.point_count ?? 0)}
          </strong>
          <span>obserwacji</span>
        </div>
      </div>
      {loading && !day && !range ? (
        <p role="status">Pobieranie historii…</p>
      ) : null}
      {range ? (
        <MonthlyChart range={range} />
      ) : (
        <>
          <h2>
            Postoje ≥
            {new Intl.NumberFormat("pl-PL").format(
              (day?.derivation.stop_seconds ?? 180) / 60,
            )}{" "}
            min
          </h2>
          {day?.stops.length ? (
            day.stops.map((s) => (
              <div className="timeline-row stop" key={s.stop_id}>
                <strong>
                  {time(s.start_at)} – {time(s.end_at)}
                </strong>
                <span>
                  Postój obserwowany · {Math.round(s.duration_seconds / 60)} min
                </span>
                {s.overlap_seconds < s.duration_seconds ? (
                  <small>
                    W tym dniu: {Math.round((s.overlap_seconds / 60) * 10) / 10}{" "}
                    min. Pełny przedział powyżej.
                  </small>
                ) : null}
                <small>
                  {s.lat.toFixed(5)}, {s.lon.toFixed(5)}
                </small>
              </div>
            ))
          ) : (
            <p className="muted">Brak obserwowanych postojów.</p>
          )}
        </>
      )}
      {gaps.length ? (
        <>
          <h2>Przerwy w trasie</h2>
          {gaps.map((g, i) => (
            <div className="timeline-row gap" key={g.from + ":" + i}>
              <strong>Brak obserwacji GPS</strong>
              <span>
                {time(g.from)} – {time(g.to)}
              </span>
            </div>
          ))}
        </>
      ) : null}
      {day && day.point_count === 0 ? (
        <p className="notice">
          Brak danych dla tego dnia. Brak pomiarów nie potwierdza postoju.
        </p>
      ) : null}
      <p className="summary-note">
        Postoje obejmują tylko obserwowany przedział. Przerwy GPS nie są
        postojami.
      </p>
    </aside>
  );
}
export function MonthlyChart({ range }: { range: Range }) {
  const max = Math.max(...range.months.map((m) => m.meters), 1);
  return (
    <section className="monthly">
      <h2>Kilometry miesięcznie</h2>
      <div role="img" aria-label="Wykres miesięczny szacowanych kilometrów GPS">
        {range.months.map((m) => (
          <div className="bar-row" key={m.month}>
            <div>
              <strong>{m.month}</strong>
              <span>{km(m.meters)} km</span>
            </div>
            <div className="bar-track">
              <div
                className="bar"
                style={{ width: `${(m.meters / max) * 100}%` }}
              />
            </div>
            {partialMonth(m.month, range.from, range.to) ? (
              <small>Część miesiąca w wybranym zakresie</small>
            ) : null}
          </div>
        ))}
      </div>
      <details>
        <summary>Dane dzienne ({range.days.length})</summary>
        <table>
          <thead>
            <tr>
              <th>Dzień</th>
              <th>km GPS</th>
              <th>Postoje</th>
            </tr>
          </thead>
          <tbody>
            {range.days.map((d) => (
              <tr key={d.date}>
                <td>{d.date}</td>
                <td>{km(d.meters)}</td>
                <td>{d.stop_count}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </section>
  );
}
