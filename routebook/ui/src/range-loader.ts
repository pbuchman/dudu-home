import { dates, geometry, type Geometry } from "./model";
import type { Adapter } from "./types";
export type Progress = {
  done: number;
  total: number;
  failed: string[];
  complete: boolean;
  cancelled: boolean;
};
// Seven-day chunks, two workers. Day payload released after its display geometry is delivered.
export async function loadRange(
  adapter: Adapter,
  from: string,
  to: string,
  signal: AbortSignal,
  add: (date: string, data: Geometry) => void,
  progress: (p: Progress) => void,
) {
  const days = dates(from, to);
  let done = 0;
  const failed: string[] = [];
  const emit = () =>
    progress({
      done,
      total: days.length,
      failed: [...failed],
      complete: done === days.length && failed.length === 0,
      cancelled: signal.aborted,
    });
  emit();
  for (let start = 0; start < days.length && !signal.aborted; start += 7) {
    const chunk = days.slice(start, start + 7);
    let index = 0;
    async function worker() {
      while (!signal.aborted && index < chunk.length) {
        const date = chunk[index++];
        try {
          const day = await adapter.day(date, signal);
          if (signal.aborted) return;
          add(date, geometry(day, true));
        } catch {
          if (signal.aborted) return;
          failed.push(date);
        }
        done++;
        emit();
      }
    }
    await Promise.all([worker(), worker()]);
  }
  if (signal.aborted) emit();
}
