import { useEffect, useRef } from "react";

/** The server chooses when a provisional tail matures; no GPS event is required. */
export function useCorrectionRefresh(
  next: string | null | undefined,
  computedAt: string | null | undefined,
  refresh: () => void,
) {
  const fired = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (!next || fired.current === next) return;
    const at = Date.parse(next);
    const computed = Date.parse(computedAt ?? "");
    if (!Number.isFinite(at) || !Number.isFinite(computed)) return;
    // Both timestamps come from the server. The browser wall clock may be wrong
    // or change while this view is open; only monotonic elapsed time is relevant.
    const due = performance.now() + Math.max(1000, at - computed);
    let timer: ReturnType<typeof setTimeout>;
    const schedule = () => {
      const delay = due - performance.now();
      if (delay <= 0) {
        fired.current = next;
        refresh();
      } else timer = setTimeout(schedule, Math.min(delay, 2 ** 31 - 1));
    };
    // A stale server deadline must not create a request loop.
    timer = setTimeout(
      schedule,
      Math.min(due - performance.now(), 2 ** 31 - 1),
    );
    return () => clearTimeout(timer);
  }, [next, computedAt, refresh]);
}
