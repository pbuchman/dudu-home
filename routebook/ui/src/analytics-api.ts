import type { PlaceWrite, PlacesResponse } from "../../contract/analytics.js";
import type { AnalyticsResponse, JourneyDetailResponse } from "./recorded-time";
export function analyticsApi(device: string) {
  async function request<T>(
    path: string,
    params: Record<string, string>,
    signal: AbortSignal,
    method = "GET",
    body?: unknown,
    attempt = 0,
  ): Promise<T> {
    const response = await fetch(
      `/v1/${path}?${new URLSearchParams({ device_id: device, ...params })}`,
      {
        signal,
        credentials: "same-origin",
        method,
        headers: {
          Accept: "application/json",
          ...(body
            ? { "Content-Type": "application/json", "X-Routebook-Write": "1" }
            : {}),
        },
        ...(body ? { body: JSON.stringify(body) } : {}),
      },
    );
    if (response.status === 429 && method === "GET" && attempt < 2) {
      const seconds = Number(response.headers.get("Retry-After") ?? "1");
      await new Promise<void>((resolve, reject) => {
        if (signal.aborted) {
          reject(new DOMException("Aborted", "AbortError"));
          return;
        }
        const abort = () => {
          clearTimeout(timer);
          reject(new DOMException("Aborted", "AbortError"));
        };
        const timer = setTimeout(
          () => {
            signal.removeEventListener("abort", abort);
            resolve();
          },
          Math.max(1, Math.min(5, Number.isFinite(seconds) ? seconds : 1)) *
            1000,
        );
        signal.addEventListener("abort", abort, { once: true });
      });
      return request<T>(path, params, signal, method, body, attempt + 1);
    }
    if (!response.ok) {
      if (response.status === 429)
        throw Error("Analizy są zajęte. Spróbuj ponownie za chwilę.");
      const value = await response.json().catch(() => ({}));
      throw Error(
        value.code === "metadata_conflict"
          ? "Miejsca zmieniły się w innej karcie. Odśwież dane i spróbuj ponownie."
          : value.code === "analytics_limit"
            ? "Za dużo obserwacji do jednego odczytu. Wybierz krótszy okres lub inną podróż."
            : value.code === "journey_not_found"
              ? "Ta podróż zmieniła się po uzupełnieniu historii. Odśwież analizy."
              : value.code === "analytics_timeout"
                ? "Obliczenie analiz trwało zbyt długo. Wybierz krótszy okres i spróbuj ponownie."
                : `Nie udało się ${method === "GET" ? "pobrać analiz" : "zapisać miejsc"} (${response.status}).`,
      );
    }
    const value = await response.json();
    if (
      value.version !== 1 ||
      value.device_id !== device ||
      !Number.isSafeInteger(value.metadata_revision) ||
      (path.startsWith("analytics") &&
        !Number.isSafeInteger(value.source_revision))
    )
      throw Error("Nieprawidłowa odpowiedź analiz.");
    return value;
  }
  return {
    summary: (from: string, to: string, signal: AbortSignal) =>
      request<AnalyticsResponse>("analytics", { from, to }, signal),
    journey: (
      from: string,
      to: string,
      journey_id: string,
      signal: AbortSignal,
    ) =>
      request<JourneyDetailResponse>(
        "analytics/journey",
        { from, to, journey_id },
        signal,
      ),
    write: (id: string | undefined, value: PlaceWrite, signal: AbortSignal) =>
      request<PlacesResponse>(
        id ? `places/${encodeURIComponent(id)}` : "places",
        {},
        signal,
        id ? "PUT" : "POST",
        value,
      ),
    remove: (id: string, revision: number, signal: AbortSignal) =>
      request<PlacesResponse>(
        `places/${encodeURIComponent(id)}`,
        {},
        signal,
        "DELETE",
        { expected_metadata_revision: revision },
      ),
  };
}
