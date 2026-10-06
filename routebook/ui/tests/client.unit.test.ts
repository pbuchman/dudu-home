import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import {
  clockState,
  dates,
  geometry,
  localDate,
  partialMonth,
  observationGaps,
  sampleSegments,
} from "../src/model";
import { loadRange } from "../src/range-loader";
import { mapData } from "../src/map-data";
import { FixtureAdapter, type FixtureFile } from "../src/fixtures";
import type { Adapter, Day, Latest, Segment } from "../src/types";
const path = process.env.ROUTEBOOK_FIXTURES_PATH ?? "../contract/fixtures.json";
if (!path) throw Error("ROUTEBOOK_FIXTURES_PATH is required");
const file: FixtureFile = JSON.parse(readFileSync(path, "utf8"));
const adapter = new FixtureAdapter(file);
const signal = () => new AbortController().signal;
describe("normative client fixtures", () => {
  it("exact 180s stop uses backend projection and meters", async () => {
    const d = await adapter.day("2026-10-05", signal());
    expect(d.meters).toBeCloseTo(111.19492664);
    expect(d.stops[0].duration_seconds).toBe(180);
    expect(d.point_count).toBe(8);
  });
  it("GPS gap remains two runs without a bridging polyline or stop", async () => {
    const a = new FixtureAdapter(file, "gps_gap_same_source_segment");
    const d = await a.day("2026-10-05", signal());
    const g = geometry(d);
    expect(g.segments).toHaveLength(2);
    expect(g.gaps).toEqual([
      { from: "2026-10-05T08:00:10.000Z", to: "2026-10-05T08:01:00.000Z" },
    ]);
    expect(d.stops).toEqual([]);
    expect(
      mapData({ a: g }, null).features.filter(
        (f) => f.geometry.type === "LineString",
      ),
    ).toHaveLength(1);
  });
  it("midnight boundary is separately rendered and not included in point_count", async () => {
    const a = new FixtureAdapter(file, "warsaw_midnight_edge");
    const d = await a.day("2026-10-05", signal());
    expect(d.point_count).toBe(1);
    expect(d.segments[0].edge_from?.measured_at).toBe(
      "2026-10-04T21:59:50.000Z",
    );
    expect(
      mapData({ a: geometry(d) }, null).features.filter(
        (f) => f.properties?.kind === "boundary",
      ),
    ).toHaveLength(1);
  });
  it("cross-midnight stop retains full interval and daily overlap", async () => {
    const a = new FixtureAdapter(file, "warsaw_midnight_stop");
    const d = await a.day("2026-10-05", signal());
    expect(d.stops[0].duration_seconds).toBe(180);
    expect(d.stops[0].overlap_seconds).toBe(90);
  });
  it("late history leaves latest at tuple max and uses final normative meters", async () => {
    const a = new FixtureAdapter(file, "latest_before_backfill_and_late_curve");
    const l = await a.latest(signal());
    expect(l.point?.measured_at).toBe("2026-10-05T08:00:20.000Z");
    expect((await a.day("2026-10-05", signal())).meters).toBeCloseTo(
      314.50674664,
    );
  });
  it("date ranges retain zero days and partial months", async () => {
    const r = await adapter.range("2026-09-30", "2026-10-06", signal());
    expect(r.days).toHaveLength(7);
    expect(r.days[0].meters).toBe(0);
    expect(r.months).toHaveLength(2);
    expect(partialMonth("2026-10", r.from, r.to)).toBe(true);
  });
});
describe("clock and display boundaries", () => {
  const l: Latest = {
    version: 1,
    device_id: file.device_id,
    revision: 1,
    point: {
      event_id: "a",
      segment_id: "b",
      device_id: file.device_id,
      received_at: "2026-10-05T08:00:00.000Z",
      measured_at: "2026-10-05T08:01:00.000Z",
      lat: 0,
      lon: 0,
      accuracy_m: 5,
      speed_mps: null,
    },
    server_time: "2026-10-05T08:00:00.000Z",
    fresh: false,
    clock_state: "future_clock",
  };
  it("future clock never live before measurement, becomes stale without event", () => {
    expect(clockState(l, 59999)).toBe("future_clock");
    expect(clockState(l, 60000)).toBe("fresh");
    expect(clockState(l, 90000)).toBe("fresh");
    expect(clockState(l, 90001)).toBe("stale");
    expect(clockState({ ...l, point: null }, 0)).toBe("empty");
  });
  it("Warsaw dates use DST offset and calendar range is not fixed local midnight math", () => {
    expect(localDate("2026-03-29T22:00:00.000Z")).toBe("2026-03-30");
    expect(localDate("2026-10-25T23:00:00.000Z")).toBe("2026-10-26");
    expect(dates("2026-03-28", "2026-03-30")).toHaveLength(3);
    expect(() => dates("2026-02-30", "2026-03-03")).toThrow();
    expect(() => dates("2025-01-01", "2026-01-02")).toThrow();
  });
  it("sampling bounds display only and preserves every run endpoint and edge", async () => {
    const d = await adapter.day("2026-10-05", signal());
    const p = d.segments[0].points[0];
    const segments: Segment[] = Array.from({ length: 3 }, (_, r) => ({
      run_id: String(r),
      source_segment_id: String(r),
      edge_from: p,
      points: Array.from({ length: 1000 }, (_, i) => ({
        ...p,
        event_id: `${r}:${i}`,
      })),
    }));
    const sampled = sampleSegments(segments);
    expect(sampled.reduce((n, s) => n + s.points.length, 0)).toBe(1000);
    sampled.forEach((s, i) => {
      expect(s.points[0]).toBe(segments[i].points[0]);
      expect(s.points.at(-1)).toBe(segments[i].points.at(-1));
      expect(s.edge_from).toBe(p);
    });
    expect(
      sampleSegments(segments, 2).reduce((n, s) => n + s.points.length, 0),
    ).toBe(6);
  });
});
describe("range queue resource and cancellation guarantees", () => {
  it("max two in-flight requests; chronological seven-day chunks; failed day labelled", async () => {
    const base = await adapter.day("2026-10-05", signal());
    let active = 0,
      max = 0;
    const requested: string[] = [];
    const received: string[] = [];
    const progress: any[] = [];
    const api = {
      ...adapter,
      day: async (date: string) => {
        active++;
        max = Math.max(active, max);
        requested.push(date);
        await new Promise((r) => setTimeout(r, 5));
        active--;
        if (date === "2026-10-03") throw Error("offline");
        return { ...base, date };
      },
    } as unknown as Adapter;
    await loadRange(
      api,
      "2026-10-01",
      "2026-10-15",
      signal(),
      (date) => received.push(date),
      (p) => progress.push(p),
    );
    expect(max).toBe(2);
    expect(requested).toEqual(dates("2026-10-01", "2026-10-15"));
    expect(received).toHaveLength(14);
    expect(progress.at(-1)).toMatchObject({
      done: 15,
      total: 15,
      failed: ["2026-10-03"],
      complete: false,
    });
  });
  it("aborted selection releases queue and discards old responses", async () => {
    const base = await adapter.day("2026-10-05", signal());
    const controller = new AbortController();
    let count = 0;
    const added: string[] = [];
    const api = {
      day: async (date: string) => {
        count++;
        await new Promise((r) => setTimeout(r, 10));
        return { ...base, date };
      },
    } as unknown as Adapter;
    const run = loadRange(
      api,
      "2026-10-01",
      "2026-10-20",
      controller.signal,
      (date) => added.push(date),
      () => {},
    );
    controller.abort();
    await run;
    expect(count).toBe(2);
    expect(added).toEqual([]);
  });
});

it("partial range never invents a GPS gap across days not yet loaded", async () => {
  const a = new FixtureAdapter(file, "gps_gap_same_source_segment");
  const d = await a.day("2026-10-05", signal());
  const one = geometry({ ...d, segments: [d.segments[0]] });
  const two = geometry({
    ...d,
    segments: [
      {
        ...d.segments[1],
        points: d.segments[1].points.map((p) => ({
          ...p,
          measured_at: "2026-10-07T08:00:00.000Z",
        })),
      },
    ],
  });
  expect(observationGaps({ "2026-10-05": one, "2026-10-07": two })).toEqual([]);
  expect(
    observationGaps({
      "2026-10-05": one,
      "2026-10-06": { segments: [], stops: [], gaps: [], points: 0 },
      "2026-10-07": two,
    }),
  ).toHaveLength(1);
});

it("future-clock has no live marker; latest marker carries freshness", async () => {
  const d = await adapter.day("2026-10-05", signal());
  const p = d.segments[0].points.at(-1)!;
  expect(
    mapData({ d: geometry(d) }, p, "future_clock").features.some(
      (f) => f.properties?.kind === "latest",
    ),
  ).toBe(false);
  expect(
    mapData({ d: geometry(d) }, p, "stale").features.find(
      (f) => f.properties?.kind === "latest",
    )?.properties?.state,
  ).toBe("stale");
});
