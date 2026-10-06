import type {
  Adapter,
  Day,
  Latest,
  Range,
  Point,
  Segment,
  Stop,
  LiveEvent,
  Derivation,
} from "./types";
import { clockState, dates, localDate } from "./model";
type Projection = {
  date: string;
  meters: number;
  point_count: number;
  run_point_ids?: string[][];
  edge_from_ids?: (string | null)[];
  stop_projection?: Omit<Stop, "lat" | "lon">[];
};
export type FixtureFile = {
  synthetic: true;
  device_id: string;
  derivation: Derivation;
  cases: FixtureCase[];
};
export type FixtureCase = {
  name: string;
  deliveries: {
    received_at: string;
    request: { points: Point[] };
    expected: {
      revision: number;
      results: { index: number; status: string }[];
    };
  }[];
  expected_queries?: { day?: Projection; days?: Projection[] };
};
export class FixtureAdapter implements Adapter {
  private listeners = new Set<
    (v: LiveEvent, k: "snapshot" | "update") => void
  >();
  private stateListeners = new Set<(s: "connected" | "offline") => void>();
  private fixture: FixtureCase;
  private points: Point[] = [];
  private revision = 0;
  private clock = 0;
  private clockBase = 0;
  private online = true;
  constructor(
    readonly file: FixtureFile,
    name = "stop_exactly_180_seconds",
  ) {
    this.fixture = file.cases[0];
    this.select(name);
  }
  select(name: string) {
    this.fixture =
      this.file.cases.find((c) => c.name === name) ?? this.file.cases[0];
    const records = new Map<string, Point>();
    for (const d of this.fixture.deliveries) {
      for (const r of d.expected.results)
        if (r.status === "accepted") {
          const p = d.request.points[r.index];
          records.set(p.event_id, {
            ...p,
            device_id: this.file.device_id,
            received_at: d.received_at,
          });
        }
      this.revision = d.expected.revision;
    }
    this.points = [...records.values()].sort(
      (a, b) =>
        a.measured_at.localeCompare(b.measured_at) ||
        a.event_id.localeCompare(b.event_id),
    );
    this.clock = Date.parse(this.fixture.deliveries.at(-1)!.received_at);
    this.clockBase = performance.now();
    this.emit("snapshot");
  }
  setClock(state: "fresh" | "stale" | "future_clock") {
    const last = this.points.at(-1);
    if (!last) return;
    this.clock =
      Date.parse(last.measured_at) +
      (state === "fresh" ? 0 : state === "stale" ? 60000 : -60000);
    this.clockBase = performance.now();
    this.emit("snapshot");
  }
  reconnect() {
    this.online = !this.online;
    for (const fn of this.stateListeners)
      fn(this.online ? "connected" : "offline");
    if (this.online) this.emit("snapshot");
  }
  update() {
    this.revision++;
    this.emit("update");
  }
  private latestValue(): Latest {
    const l: Latest = {
      version: 1,
      device_id: this.file.device_id,
      revision: this.revision,
      server_time: new Date(
        this.clock + performance.now() - this.clockBase,
      ).toISOString(),
      point: this.points.at(-1) ?? null,
      fresh: false,
      clock_state: "empty",
    };
    l.clock_state = clockState(l, 0);
    l.fresh = l.clock_state === "fresh";
    return l;
  }
  private emit(kind: "snapshot" | "update") {
    if (!this.online) return;
    const event: LiveEvent = {
      version: 1,
      device_id: this.file.device_id,
      revision: this.revision,
      latest: this.latestValue(),
      affected_dates:
        kind === "snapshot"
          ? []
          : [...new Set(this.points.map((p) => localDate(p.measured_at)))],
    };
    for (const f of this.listeners) f(event, kind);
  }
  private async delay(signal: AbortSignal) {
    await new Promise<void>((resolve, reject) => {
      if (signal.aborted) {
        reject(new DOMException("Aborted", "AbortError"));
        return;
      }
      const abort = () => {
        clearTimeout(timer);
        reject(new DOMException("Aborted", "AbortError"));
      };
      const timer = setTimeout(() => {
        signal.removeEventListener("abort", abort);
        this.online
          ? resolve()
          : reject(Error("Tryb offline: dane demonstracyjne niedostępne."));
      }, 160);
      signal.addEventListener("abort", abort, { once: true });
    });
  }
  async latest(signal: AbortSignal) {
    await this.delay(signal);
    return this.latestValue();
  }
  async day(date: string, signal: AbortSignal): Promise<Day> {
    await this.delay(signal);
    const queries = this.fixture.expected_queries;
    const projection =
      queries?.day?.date === date
        ? queries.day
        : queries?.days?.find((d) => d.date === date);
    const points = this.points.filter((p) => localDate(p.measured_at) === date);
    const ids =
      projection?.run_point_ids ??
      (points.length ? [points.map((p) => p.event_id)] : []);
    const segments: Segment[] = ids.map((run, i) => {
      const list = run
        .map((id) => points.find((p) => p.event_id === id)!)
        .filter(Boolean);
      const predecessor = projection?.edge_from_ids?.[i];
      return {
        run_id: list[0].event_id,
        source_segment_id: list[0].segment_id,
        points: list,
        edge_from: this.points.find((p) => p.event_id === predecessor) ?? null,
      };
    });
    const stops = (projection?.stop_projection ?? []).map((s) => {
      const p = this.points.find((p) => p.event_id === s.stop_id)!;
      return { ...s, lat: p.lat, lon: p.lon };
    });
    return {
      version: 1,
      device_id: this.file.device_id,
      revision: this.revision,
      date,
      timezone: "Europe/Warsaw",
      derivation: this.file.derivation,
      meters: projection?.meters ?? 0,
      point_count: points.length,
      segments,
      stops,
    };
  }
  async range(from: string, to: string, signal: AbortSignal): Promise<Range> {
    await this.delay(signal);
    const queries = this.fixture.expected_queries;
    const projections = [
      ...(queries?.days ?? []),
      ...(queries?.day ? [queries.day] : []),
    ];
    const days = dates(from, to).map((date) => {
      const d = projections.find((p) => p.date === date);
      return {
        date,
        meters: d?.meters ?? 0,
        point_count: d?.point_count ?? 0,
        stop_count: d?.stop_projection?.length ?? 0,
      };
    });
    const months = new Map<string, number>();
    for (const d of days)
      months.set(
        d.date.slice(0, 7),
        (months.get(d.date.slice(0, 7)) ?? 0) + d.meters,
      );
    return {
      version: 1,
      device_id: this.file.device_id,
      revision: this.revision,
      from,
      to,
      timezone: "Europe/Warsaw",
      derivation: this.file.derivation,
      days,
      months: [...months].map(([month, meters]) => ({ month, meters })),
      meters: days.reduce((sum, d) => sum + d.meters, 0),
    };
  }
  subscribe(
    event: (v: LiveEvent, k: "snapshot" | "update") => void,
    status: (s: "connected" | "offline") => void,
  ) {
    this.listeners.add(event);
    this.stateListeners.add(status);
    queueMicrotask(() => {
      status("connected");
      this.emit("snapshot");
    });
    return () => {
      this.listeners.delete(event);
      this.stateListeners.delete(status);
    };
  }
}
