import { describe, expect, it } from "vitest";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { Summary } from "../src/Summary";
import { correctionLabel } from "../src/InCarTime";
import { recordedTime } from "../src/recorded-time";
import type { Day, Range } from "../src/types";

const day: Day = {
  version: 1,
  device_id: "synthetic",
  revision: 1,
  date: "2026-10-14",
  timezone: "Europe/Warsaw",
  derivation: {
    gap_seconds: 30,
    max_edge_speed_mps: 70,
    stationary_speed_mps: 1,
    min_edge_m: 1,
    stop_radius_m: 30,
    stop_seconds: 180,
    earth_radius_m: 6371000,
  },
  meters: 1234,
  point_count: 100,
  segments: [],
  stops: [],
  recorded_seconds: 2700,
};
const render = (props: Partial<Parameters<typeof Summary>[0]> = {}) =>
  renderToStaticMarkup(
    createElement(Summary, { day, parts: {}, loading: false, ...props }),
  );

describe("recorded GPS time presentation", () => {
  it("keeps absent, invalid, no observations and observed zero distinct", () => {
    for (const value of [undefined, null, NaN, Infinity, -1])
      expect(recordedTime(value)).toBe("Niedostępne");
    expect(recordedTime(0)).toBe("0 s");
    expect(recordedTime(0, false)).toBe("Brak danych");
  });
  it("rounds only at display, retains seconds and never wraps at 24 hours", () => {
    expect(recordedTime(59.6)).toBe("1 min");
    expect(recordedTime(3600.4)).toBe("1 godz. 0 min");
    expect(recordedTime(90061)).toBe("25 godz. 1 min 1 s");
    expect(recordedTime(2700)).toBe("45 min");
  });
  it("binds the authoritative day value independently of map geometry and stops", () => {
    expect(render()).toContain("45 min");
    expect(render()).toContain("Zapis GPS (surowy)");
    expect(render({ day: { ...day, recorded_seconds: undefined } })).toContain(
      "Niedostępne",
    );
    expect(render({ day: { ...day, recorded_seconds: null } })).toContain(
      "Niedostępne",
    );
    expect(render({ day: { ...day, recorded_seconds: 0 } })).toContain("0 s");
    expect(
      render({ day: { ...day, point_count: 0, recorded_seconds: 0 } }),
    ).toContain("Brak danych");
  });
  it("does not turn loading or failed requests into a measured zero", () => {
    const loading = render({ day: undefined, loading: true });
    expect(loading).toContain("Pobieranie…");
    expect(loading).not.toContain("0 s");
    expect(render({ error: "offline" })).toContain("Błąd pobierania");
    expect(render({ error: "offline" })).not.toContain("45 min");
  });
  it("uses backend range, month and day fields separately without summing rounded cells", () => {
    const range: Range = {
      version: 1,
      device_id: day.device_id,
      revision: 1,
      timezone: day.timezone,
      derivation: day.derivation,
      from: "2026-10-14",
      to: "2026-10-15",
      meters: 1234,
      recorded_seconds: 3300,
      days: [
        {
          date: "2026-10-14",
          meters: 1234,
          point_count: 100,
          stop_count: 0,
          recorded_seconds: 2700,
        },
        {
          date: "2026-10-15",
          meters: 0,
          point_count: 25,
          stop_count: 0,
          recorded_seconds: 600,
        },
      ],
      months: [{ month: "2026-10", meters: 1234, recorded_seconds: 3300 }],
    };
    const html = render({ day: undefined, range });
    expect(html).toContain("55 min");
    expect(html).toContain("45 min");
    expect(html).toContain("10 min");
    expect(html).toContain('scope="col">Czas w samochodzie');
    expect(render({ range: { ...range, recorded_seconds: 90000 } })).toContain(
      "25 godz. 0 min",
    );
  });
});

describe("corrected time presentation", () => {
  const correction = {
    in_car_seconds: 1800,
    excluded_tail_seconds: 900,
    confirmed_excluded_seconds: 0,
    estimated_excluded_seconds: 900,
    provisional_excluded_seconds: 300,
  };
  it("promotes corrected time while preserving raw and reason breakdown", () => {
    const html = render({ day: { ...day, ...correction } });
    expect(html).toContain("30 min");
    expect(html).toContain("45 min");
    expect(html).toContain("15 min");
    expect(html).toContain("5 min");
    expect(html).toContain("Czas szacowany · tymczasowy");
    expect(html).toContain("Ręczny licznik radia jest niezależny");
    expect(html).not.toContain("Korekta potwierdzona sygnałem zasilania");
  });
  it("distinguishes historical inference, confirmed power evidence and unknown detail", () => {
    expect(
      correctionLabel({ ...correction, provisional_excluded_seconds: 0 }),
    ).toContain("szacowany");
    expect(
      correctionLabel({
        ...correction,
        provisional_excluded_seconds: 0,
        estimated_excluded_seconds: 0,
        confirmed_excluded_seconds: 900,
      }),
    ).toBe("Korekta potwierdzona sygnałem zasilania");
    expect(correctionLabel({ in_car_seconds: 1800 })).toContain(
      "szczegóły niedostępne",
    );
    expect(correctionLabel({ recorded_seconds: 1800 })).toContain("surowy");
  });
  it("does not turn no observations or a missing correction into confirmed zero", () => {
    const empty = render({ day: { ...day, ...correction, point_count: 0 } });
    expect(empty).toContain("Brak danych");
    const legacy = render();
    expect(legacy).toContain("korekta niedostępna");
    expect(legacy).toContain("Niedostępne");
  });
});
