import { useEffect, useRef } from "react";

/** The server chooses when a provisional tail matures; no GPS event is required. */
export function useCorrectionRefresh(
  next: string | null | undefined,
  refresh: () => void,
) {
  const fired = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (!next || fired.current === next) return;
    const at = Date.parse(next);
    if (!Number.isFinite(at)) return;
    let timer: ReturnType<typeof setTimeout>;
    const schedule = () => {
      const delay = at - Date.now();
      if (delay <= 0) {
        fired.current = next;
        refresh();
      } else timer = setTimeout(schedule, Math.min(delay, 2 ** 31 - 1));
    };
    // A stale server deadline must not create a request loop.
    timer = setTimeout(
      schedule,
      Math.max(1000, Math.min(at - Date.now(), 2 ** 31 - 1)),
    );
    return () => clearTimeout(timer);
  }, [next, refresh]);
}
