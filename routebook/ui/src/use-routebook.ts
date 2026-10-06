import { useEffect, useRef, useState } from "react";
import type { Adapter, Day, Latest, Range } from "./types";
import { clockState, geometry, type Geometry } from "./model";
import { loadRange, type Progress } from "./range-loader";
export type Selection = {
  mode: "day" | "live" | "range";
  date: string;
  from: string;
  to: string;
};
export function useRoutebook(adapter: Adapter, selection: Selection) {
  const [latest, setLatest] = useState<Latest>();
  const [day, setDay] = useState<Day>();
  const [range, setRange] = useState<Range>();
  const [parts, setParts] = useState<Record<string, Geometry>>({});
  const [progress, setProgress] = useState<Progress>();
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [connection, setConnection] = useState<
    "connecting" | "connected" | "offline"
  >("connecting");
  const [refresh, setRefresh] = useState(0);
  const [, setTick] = useState(0);
  const clock = useRef(0);
  const revision = useRef(-1);
  const current = useRef(selection);
  current.current = selection;
  const rangeAbort = useRef<AbortController | undefined>(undefined);
  const rangeLoading = useRef(false);
  const pendingUpdate = useRef(false);
  const previousSelection = useRef("");
  const applyLatest = (value: Latest) => {
    if (value.revision < revision.current) return;
    revision.current = value.revision;
    clock.current = performance.now();
    setLatest(value);
  };
  useEffect(() => {
    revision.current = -1;
    setLatest(undefined);
    setConnection("connecting");
    const initial = new AbortController();
    adapter
      .latest(initial.signal)
      .then((value) => {
        if (!initial.signal.aborted) applyLatest(value);
      })
      .catch(() => {});
    const unsubscribe = adapter.subscribe((event, kind) => {
      const previous = revision.current;
      if (kind === "update" && event.revision < previous) return;
      applyLatest(event.latest);
      const s = current.current;
      const relevant =
        s.mode === "range"
          ? event.affected_dates.some((d) => d >= s.from && d <= s.to)
          : event.affected_dates.includes(s.date);
      if (kind === "snapshot" || event.revision > previous + 1 || relevant) {
        // Frequent commits must not starve a long range load.
        if (kind === "update" && s.mode === "range" && rangeLoading.current)
          pendingUpdate.current = true;
        else setRefresh((n) => n + 1);
      }
    }, setConnection);
    return () => {
      initial.abort();
      unsubscribe();
    };
  }, [adapter]);
  useEffect(() => {
    const id = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    const mapController = new AbortController();
    rangeAbort.current = mapController;
    rangeLoading.current = selection.mode === "range";
    pendingUpdate.current = false;
    const selectionKey = JSON.stringify(selection);
    const changed = previousSelection.current !== selectionKey;
    previousSelection.current = selectionKey;
    setError("");
    setLoading(true);
    if (changed) {
      setDay(undefined);
      setRange(undefined);
      setParts({});
    }
    setProgress(undefined);
    async function fetchSelection() {
      try {
        if (selection.mode === "range") {
          const summary = adapter
            .range(selection.from, selection.to, controller.signal)
            .then((value) => {
              if (!controller.signal.aborted) setRange(value);
            });
          const map = loadRange(
            adapter,
            selection.from,
            selection.to,
            mapController.signal,
            (date, data) => {
              if (!controller.signal.aborted && !mapController.signal.aborted)
                setParts((prev) => ({ ...prev, [date]: data }));
            },
            (value) => {
              if (!controller.signal.aborted && !mapController.signal.aborted)
                setProgress(value);
            },
          );
          await Promise.all([summary, map]);
        } else {
          const value = await adapter.day(selection.date, controller.signal);
          if (!controller.signal.aborted) {
            setDay(value);
            setParts({ [selection.date]: geometry(value) });
          }
        }
      } catch (e) {
        if (!controller.signal.aborted) {
          setError(e instanceof Error ? e.message : "Błąd pobierania danych.");
          mapController.abort();
          setProgress((p) =>
            p ? { ...p, complete: false, cancelled: true } : p,
          );
        }
      } finally {
        if (!controller.signal.aborted) {
          rangeLoading.current = false;
          setLoading(false);
          if (pendingUpdate.current && !mapController.signal.aborted) {
            pendingUpdate.current = false;
            setRefresh((n) => n + 1);
          }
        }
      }
    }
    void fetchSelection();
    return () => {
      controller.abort();
      mapController.abort();
    };
  }, [
    adapter,
    selection.mode,
    selection.date,
    selection.from,
    selection.to,
    refresh,
  ]);
  const cancel = () => {
    rangeAbort.current?.abort();
    rangeLoading.current = false;
    pendingUpdate.current = false;
    setLoading(false);
    setProgress((p) => (p ? { ...p, cancelled: true, complete: false } : p));
  };
  return {
    latest,
    day,
    range,
    parts,
    progress,
    error,
    loading,
    connection,
    state: clockState(latest, performance.now() - clock.current),
    refetch: () => setRefresh((n) => n + 1),
    cancel,
  };
}
