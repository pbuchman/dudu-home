import type { Adapter, Day, Range, Latest, LiveEvent } from "./types";
export function apiAdapter(device: string): Adapter {
  const url = (route: string, params: Record<string, string> = {}) =>
    `/v1/${route}?${new URLSearchParams({ device_id: device, ...params })}`;
  async function get<T>(
    route: string,
    params: Record<string, string>,
    signal: AbortSignal,
  ): Promise<T> {
    const r = await fetch(url(route, params), {
      signal,
      credentials: "same-origin",
      headers: { Accept: "application/json" },
    });
    if (!r.ok)
      throw Error(
        r.status === 404
          ? "Urządzenie nie jest zarejestrowane."
          : r.status === 503
            ? "Archiwum jest chwilowo niedostępne."
            : `Nie udało się pobrać danych (${r.status}).`,
      );
    const value = await r.json();
    if (
      value.version !== 1 ||
      value.device_id !== device ||
      !Number.isSafeInteger(value.revision)
    )
      throw Error("Nieprawidłowa odpowiedź archiwum.");
    return value;
  }
  return {
    day: (date, signal) => get<Day>("day", { date }, signal),
    range: (from, to, signal) => get<Range>("range", { from, to }, signal),
    latest: (signal) => get<Latest>("latest", {}, signal),
    subscribe(event, status) {
      const source = new EventSource(url("live"));
      let revision = -1;
      source.onopen = () => {
        revision = -1;
        status("connected");
      };
      source.onerror = () => status("offline");
      for (const kind of ["snapshot", "update"] as const)
        source.addEventListener(kind, (raw) => {
          try {
            const data: LiveEvent = JSON.parse((raw as MessageEvent).data);
            if (
              data.version !== 1 ||
              data.device_id !== device ||
              !Number.isSafeInteger(data.revision) ||
              data.latest.device_id !== device ||
              data.latest.revision !== data.revision ||
              !Array.isArray(data.affected_dates)
            )
              throw Error();
            if (data.revision < revision) return;
            revision = data.revision;
            event(data, kind);
          } catch {
            status("offline");
          }
        });
      return () => source.close();
    },
  };
}
