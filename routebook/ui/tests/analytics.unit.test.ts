import { describe, expect, it, vi } from "vitest";
import {
  duration,
  nearestObservation,
  periodBounds,
  replayStep,
  sameRevision,
  speed,
} from "../src/analytics-model";
import { mapData } from "../src/map-data";
import { analyticsFixture, points } from "./analytics-fixture";
import { analyticsApi } from "../src/analytics-api";
describe("private analytics display and replay", () => {
  it("keeps missing speed distinct from observed zero", () => {
    expect(speed(null)).toBe("Brak danych");
    expect(speed(0)).toBe("0 km/h");
    expect(duration(null)).toBe("Brak danych");
  });
  it("uses Monday weeks and real month lengths across leap year and DST dates", () => {
    expect(periodBounds("2026-10-25", "week")).toEqual({
      from: "2026-10-19",
      to: "2026-10-25",
    });
    expect(periodBounds("2024-02-19", "month")).toEqual({
      from: "2024-02-01",
      to: "2024-02-29",
    });
  });
  it("holds at an actual observation then jumps a disconnected run without a bridge", () => {
    const { detail } = analyticsFixture();
    const start = Date.parse(points[0].measured_at);
    expect(replayStep(detail, 0, start + 5000)).toEqual({
      cursor: 0,
      clock: start + 5000,
      skipped: 0,
    });
    expect(replayStep(detail, 1, start + 20000)).toEqual({
      cursor: 3,
      clock: start + 80000,
      skipped: 60,
    });
  });
  it("jumps gaps even when elapsed frame crosses the whole missing interval", () => {
    const { detail } = analyticsFixture();
    expect(replayStep(detail, 0, Date.parse(points[5].measured_at))).toEqual({
      cursor: 3,
      clock: Date.parse(points[3].measured_at),
      skipped: 60,
    });
  });
  it("rejects combining changed source, place or algorithm revisions", () => {
    const { summary, detail } = analyticsFixture();
    expect(sameRevision(detail, summary)).toBe(true);
    for (const changed of [
      { source_revision: 2 },
      { metadata_revision: 1 },
      { algorithm_version: "other" },
    ])
      expect(sameRevision(detail, { ...summary, ...changed })).toBe(false);
  });
  it("selects raw observations from map position and separates historical from live marker", () => {
    expect(nearestObservation(points, 0.006, 0)).toBe(3);
    const result = mapData({}, points[0], "fresh", points[3]);
    expect(result.features.map((f) => f.properties?.kind)).toEqual([
      "latest",
      "historical",
    ]);
  });
});
describe("analytics API writes", () => {
  it("uses same-origin credentials, write header and optimistic revision", async () => {
    const fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        version: 1,
        device_id: points[0].device_id,
        metadata_revision: 2,
        places: [],
      }),
    });
    vi.stubGlobal("fetch", fetch);
    try {
      await analyticsApi(points[0].device_id).write(
        undefined,
        { name: "Synthetic", lat: 0, lon: 0, expected_metadata_revision: 1 },
        new AbortController().signal,
      );
      expect(fetch.mock.calls[0][1]).toMatchObject({
        credentials: "same-origin",
        method: "POST",
        headers: {
          "X-Routebook-Write": "1",
          "Content-Type": "application/json",
        },
      });
      expect(
        JSON.parse(fetch.mock.calls[0][1].body).expected_metadata_revision,
      ).toBe(1);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
