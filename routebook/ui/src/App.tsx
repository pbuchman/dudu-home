import { useState } from "react";
import type { Adapter, RuntimeConfig } from "./types";
import { FixtureAdapter } from "./fixtures";
import { clockLabel, dates, localDate, time } from "./model";
import { useRoutebook, type Selection } from "./use-routebook";
import MapView from "./MapView";
import { Summary } from "./Summary";
export default function App({
  adapter,
  config,
}: {
  adapter: Adapter;
  config: RuntimeConfig;
}) {
  const [activeAdapter, setActiveAdapter] = useState(adapter);
  const demo = activeAdapter instanceof FixtureAdapter ? activeAdapter : null;
  const [selection, setSelection] = useState<Selection>({
    mode: "day",
    date: demo ? "2026-10-05" : localDate(Date.now()),
    from: "2026-10-04",
    to: demo ? "2026-10-05" : localDate(Date.now()),
  });
  const [draft, setDraft] = useState({
    from: selection.from,
    to: selection.to,
  });
  const [validation, setValidation] = useState("");
  const [scenario, setScenario] = useState("stop_exactly_180_seconds");
  const data = useRoutebook(activeAdapter, selection);
  function selectMode(mode: Selection["mode"]) {
    setSelection((s) => ({
      ...s,
      mode,
      date:
        mode === "live"
          ? data.latest?.point
            ? localDate(data.latest.point.measured_at)
            : localDate(Date.now())
          : s.date,
    }));
  }
  function rangeSubmit(e: React.FormEvent) {
    e.preventDefault();
    try {
      dates(draft.from, draft.to);
      setValidation("");
      setSelection((s) => ({ ...s, ...draft }));
    } catch (e) {
      setValidation((e as Error).message);
    }
  }
  const progress = data.progress;
  return (
    <>
      <header className="header">
        <div className="brand">
          <strong>Routebook</strong>
          <span>DUDU7 · prywatna historia</span>
        </div>
        <span className="privacy">
          {demo ? "Dane syntetyczne" : "Prywatne archiwum"}
        </span>
      </header>
      <nav className="toolbar" aria-label="Widok historii">
        <div className="tabs">
          {(["day", "live", "range"] as const).map((mode, i) => (
            <button
              key={mode}
              aria-pressed={selection.mode === mode}
              onClick={() => selectMode(mode)}
            >
              {["Dzień", "Na żywo", "Zakres"][i]}
            </button>
          ))}
        </div>
        {selection.mode === "range" ? (
          <form className="date-form" onSubmit={rangeSubmit}>
            <label>
              Od
              <input
                aria-label="Od"
                type="date"
                value={draft.from}
                onChange={(e) =>
                  setDraft((d) => ({ ...d, from: e.target.value }))
                }
              />
            </label>
            <label>
              Do
              <input
                aria-label="Do"
                type="date"
                value={draft.to}
                onChange={(e) =>
                  setDraft((d) => ({ ...d, to: e.target.value }))
                }
              />
            </label>
            <button type="submit" className="primary">
              Pokaż zakres
            </button>
          </form>
        ) : (
          <input
            type="date"
            aria-label="Wybrany dzień"
            value={selection.date}
            onChange={(e) => {
              if (e.target.value)
                setSelection((s) => ({ ...s, date: e.target.value }));
            }}
          />
        )}
        <button className="primary refresh" onClick={data.refetch}>
          Odśwież
        </button>
      </nav>
      {validation ? (
        <p className="banner warning" role="alert">
          {validation}
        </p>
      ) : null}
      {demo ? (
        <details className="demo-controls">
          <summary>Scenariusze demonstracyjne</summary>
          <div>
            <label>
              Scenariusz
              <select
                aria-label="Scenariusz"
                value={scenario}
                onChange={(e) => {
                  setScenario(e.target.value);
                  setActiveAdapter(
                    new FixtureAdapter(demo.file, e.target.value),
                  );
                }}
              >
                {demo.file.cases
                  .filter((c) => c.name !== "warsaw_dst_day_bounds")
                  .map((c) => (
                    <option key={c.name} value={c.name}>
                      {(
                        {
                          gps_gap_same_source_segment: "Luka GPS",
                          stop_exactly_180_seconds: "Postój: dokładnie 3 min",
                          latest_before_backfill_and_late_curve:
                            "Spóźniona historia",
                          commit_then_lost_ack_retry_and_conflict:
                            "Ponowienie i konflikt",
                          valid_and_invalid_mixed_batch:
                            "Częściowo odrzucona paczka",
                          warsaw_midnight_edge: "Trasa przez północ",
                          warsaw_midnight_stop: "Postój przez północ",
                          future_clock_not_live: "Pomiar w przyszłości",
                        } as Record<string, string>
                      )[c.name] ?? c.name}
                    </option>
                  ))}
              </select>
            </label>
            <button onClick={() => demo.setClock("fresh")}>
              Świeży pomiar
            </button>
            <button onClick={() => demo.setClock("stale")}>Stary pomiar</button>
            <button onClick={() => demo.setClock("future_clock")}>
              Zegar w przyszłości
            </button>
            <button onClick={() => demo.update()}>Aktualizacja SSE</button>
            <button onClick={() => demo.reconnect()}>
              Offline / połącz ponownie
            </button>
          </div>
        </details>
      ) : null}
      {data.error ? (
        <div className="banner warning" role="alert">
          {data.error} <button onClick={data.refetch}>Ponów pobranie</button>
        </div>
      ) : null}
      <main className="workspace">
        <Summary
          day={data.day}
          range={data.range}
          parts={data.parts}
          loading={data.loading}
        />
        <section className="map-column">
          <div className="position-strip">
            <span className={`status-dot ${data.state}`} />
            <strong>{clockLabel[data.state]}</strong>
            {data.latest?.point ? (
              <span>
                Pomiar: {localDate(data.latest.point.measured_at)} ·{" "}
                {time(data.latest.point.measured_at)}
              </span>
            ) : null}
            <span className="connection">
              {data.connection === "connected"
                ? "Połączono"
                : data.connection === "offline"
                  ? "Offline · ponawianie połączenia"
                  : "Łączenie…"}
            </span>
          </div>
          {data.state === "future_clock" ? (
            <p className="notice warning" role="alert">
              Czas pomiaru jest w przyszłości. Pozycja nie jest jeszcze
              wiarygodnym pomiarem na żywo.
            </p>
          ) : null}
          {selection.mode === "range" ? (
            <div className="range-progress" aria-live="polite">
              <span>
                Mapa uproszczona · {progress?.done ?? 0}/{progress?.total ?? 0}{" "}
                dni
                {progress?.cancelled
                  ? " · anulowano"
                  : progress?.complete
                    ? " · pobrano wszystkie dni"
                    : " · mapa częściowa"}
              </span>
              {data.loading ? (
                <button onClick={data.cancel}>Anuluj pobieranie</button>
              ) : null}
              {progress?.failed.length ? (
                <span className="warning">
                  Nie pobrano: {progress.failed.join(", ")}
                </span>
              ) : null}
            </div>
          ) : null}
          <MapView
            config={config}
            latestState={data.state}
            parts={data.parts}
            latest={
              (selection.mode === "live" ||
                (selection.mode === "day" &&
                  data.latest?.point &&
                  localDate(data.latest.point.measured_at) ===
                    selection.date)) &&
              data.state !== "future_clock"
                ? (data.latest?.point ?? null)
                : null
            }
            selectionKey={JSON.stringify(selection)}
          />
          <div className="map-footer">
            <div className="legend">
              <span>
                <i className="route-key" />
                Trasa
              </span>
              <span>
                <i className="stop-key" />
                Postój
              </span>
              <span>
                <i className="gap-key" />
                Przerwa GPS
              </span>
            </div>
            <span>Odległość szacowana z GPS · Europe/Warsaw</span>
          </div>
        </section>
      </main>
    </>
  );
}
