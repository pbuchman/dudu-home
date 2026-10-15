import { test, expect, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { analyticsFixture, device } from "./analytics-fixture";
const evidence = process.env.ROUTEBOOK_UI_EVIDENCE_DIR ?? "/tmp/dudu-recorded-time-ui";
mkdirSync(evidence, { recursive: true });

async function harness(page: Page, state: "valid" | "missing" | "null" | "zero" | "empty" | "error" = "valid") {
  const fixtures = analyticsFixture();
  let revision = 1;
  let slow = false;
  const values = (seconds: number) => state === "missing" ? {} : {
    recorded_seconds: state === "null" ? null : ["zero", "empty"].includes(state) ? 0 : seconds,
  };
  const latest = () => ({ version: 1, device_id: device, revision,
    server_time: "2026-10-14T12:00:00Z", point: null, fresh: false, clock_state: "empty" });
  await page.clock.setFixedTime(new Date("2026-10-14T12:00:00Z"));
  await page.addInitScript(() => {
    class Source extends EventTarget {
      onopen: (() => void) | null = null;
      constructor() { super(); (window as any).recordedSSE = this; setTimeout(() => this.onopen?.(), 0); }
      close() {}
    }
    (window as any).EventSource = Source;
  });
  await page.route("**/routebook-config.json", r => r.fulfill({ json: { mode: "api", deviceId: device } }));
  await page.route("**/v1/**", async r => {
    const url = new URL(r.request().url());
    const from = url.searchParams.get("from") ?? "2026-10-14";
    const to = url.searchParams.get("to") ?? from;
    const captured = revision;
    const date = url.searchParams.get("date") ?? "2026-10-14";
    if (url.pathname === "/v1/latest") return r.fulfill({ json: latest() });
    if (slow) await new Promise(resolve => setTimeout(resolve, date === "2026-10-13" ? 700 : 180));
    if (state === "error") return r.fulfill({ status: 503, json: { code: "unavailable" } });
    const point_count = state === "empty" ? 0 : 100;
    const derivation = { algorithm_version: "routebook-recorded-time-1", gap_seconds: 30 };
    const base = { version: 1, device_id: device, revision: captured, timezone: "Europe/Warsaw",
      derivation: { stop_seconds: 180 }, recorded_time_derivation: derivation, meters: 0 };
    const daily = [
      { date: "2026-10-14", meters: 0, point_count, stop_count: 0, ...values(captured === 1 ? 2700 : 3000) },
      { date: "2026-10-15", meters: 0, point_count, stop_count: 0, ...values(600) },
    ];
    if (url.pathname === "/v1/day") return r.fulfill({ json: { ...base, date, point_count, segments: [], stops: [],
      ...values(date === "2026-10-15" ? 600 : date === "2026-10-13" ? 123 : captured === 1 ? 2700 : 3000) } });
    if (url.pathname === "/v1/range") return r.fulfill({ json: { ...base, from, to, days: daily,
      months: [{ month: "2026-10", meters: 0, ...values(3300) }], ...values(3300) } });
    if (url.pathname === "/v1/analytics") {
      const period = fixtures.summary.days[0];
      return r.fulfill({ json: { ...fixtures.summary, from, to, source_revision: captured,
        recorded_time_derivation: derivation, journeys: [], visits: [], comparisons: [],
        totals: { ...fixtures.summary.totals, point_count, ...values(from === to ? 2700 + (captured - 1) * 300 : 3300) },
        days: daily.map(d => ({ ...period, period: d.date, point_count, ...values(d.recorded_seconds ?? 0) })),
        weeks: [{ ...period, period: "2026-10-12", point_count, ...values(3300) }],
        months: [{ ...period, period: "2026-10", point_count, ...values(3300) }],
      } });
    }
    return r.fulfill({ status: 404 });
  });
  return { slow: () => { slow = true; }, update: async () => {
    revision++;
    await page.evaluate(data => (window as any).recordedSSE.dispatchEvent(
      new MessageEvent("update", { data: JSON.stringify(data) })),
      { version: 1, device_id: device, revision, latest: latest(), affected_dates: ["2026-10-14"] });
  } };
}
const metric = (page: Page) => page.locator(".recorded-metric dd").first();

test("API day, range, month and analytics periods show independent authoritative times", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", e => errors.push(e.message));
  page.on("console", m => { if (["error", "warning"].includes(m.type())) errors.push(m.text()); });
  await harness(page);
  await page.goto("/");
  expect(new URL(page.url()).pathname).toBe("/");
  await expect(page).toHaveTitle(/Routebook/);
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  await expect(metric(page)).toHaveText("45 min");
  await expect(page.getByText("na podstawie zapisu GPS", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Zakres", exact: true }).click();
  await page.getByLabel("Od", { exact: true }).fill("2026-10-14");
  await page.getByLabel("Do", { exact: true }).fill("2026-10-15");
  await page.getByRole("button", { name: "Pokaż zakres" }).click();
  await expect(metric(page)).toHaveText("55 min");
  await expect(page.locator(".month-recorded")).toContainText("55 min");
  await page.getByText("Dane dzienne (2)", { exact: true }).click();
  await expect(page.getByRole("row").filter({ hasText: "2026-10-14" })).toContainText("45 min");
  await expect(page.getByRole("row").filter({ hasText: "2026-10-15" })).toContainText("10 min");
  await page.screenshot({ path: `${evidence}/recorded-time-range-desktop.png`, fullPage: true });
  await page.getByRole("button", { name: "Analizy", exact: true }).click();
  await expect(metric(page)).toHaveText("45 min");
  await page.getByRole("button", { name: "Tydzień", exact: true }).click();
  await expect(metric(page)).toHaveText("55 min");
  await page.getByRole("button", { name: "Miesiąc", exact: true }).click();
  await expect(metric(page)).toHaveText("55 min");
  await page.getByText("Czas w samochodzie według dni, tygodni i miesięcy", { exact: true }).click();
  for (const name of ["Dni", "Tygodnie od poniedziałku", "Miesiące"])
    await expect(page.getByRole("region", { name, exact: true }).getByRole("table")).toBeVisible();
  await expect(page.getByRole("region", { name: "Dni", exact: true })).toContainText("10 min");
  await page.screenshot({ path: `${evidence}/recorded-time-analytics-desktop.png`, fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: `${evidence}/recorded-time-analytics-mobile.png`, fullPage: true });
  await page.getByRole("button", { name: "Zakres", exact: true }).click();
  await expect(metric(page)).toHaveText("55 min");
  await page.getByText("Dane dzienne (2)", { exact: true }).click();
  await expect(page.getByRole("columnheader", { name: "Czas w samochodzie", exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: `${evidence}/recorded-time-range-mobile.png`, fullPage: true });
  expect(errors).toEqual([]);
});

for (const [state, expected] of [["missing", "Niedostępne"], ["null", "Niedostępne"], ["zero", "0 s"], ["empty", "Brak danych"]] as const)
  test(`${state} is explicit in day and analytics`, async ({ page }) => {
    await harness(page, state);
    await page.goto("/");
    await expect(metric(page)).toHaveText(expected);
    await page.getByRole("button", { name: "Analizy", exact: true }).click();
    await expect(metric(page)).toHaveText(expected);
  });

test("late backfill refreshes authoritative time; slow old selection cannot overwrite new day", async ({ page }) => {
  const control = await harness(page);
  await page.goto("/");
  await expect(metric(page)).toHaveText("45 min");
  await control.update();
  await expect(metric(page)).toHaveText("50 min");
  control.slow();
  await page.getByLabel("Wybrany dzień").fill("2026-10-13");
  await expect(metric(page)).toHaveText("Pobieranie…");
  await page.getByLabel("Wybrany dzień").fill("2026-10-15");
  await expect(metric(page)).toHaveText("10 min");
  await page.waitForTimeout(800);
  await expect(metric(page)).toHaveText("10 min");
  await page.getByRole("button", { name: "Analizy", exact: true }).click();
  await expect(metric(page)).toHaveText("50 min");
  await control.update();
  await expect(metric(page)).toHaveText("55 min");
});

test("analytics uses total and each period field directly, with time beyond 24 hours", async ({ page }) => {
  await harness(page);
  const { summary } = analyticsFixture();
  const row = summary.days[0];
  await page.route("**/v1/analytics?**", r => r.fulfill({ json: {
    ...summary,
    totals: { ...summary.totals, recorded_seconds: 90061, point_count: 100 },
    days: [{ ...row, recorded_seconds: 2700, point_count: 100 }],
    weeks: [{ ...row, recorded_seconds: 600, point_count: 100 }],
    months: [{ ...row, recorded_seconds: 123, point_count: 100 }],
  } }));
  await page.goto("/");
  await page.getByRole("button", { name: "Analizy", exact: true }).click();
  await expect(metric(page)).toHaveText("25 godz. 1 min 1 s");
  await page.getByText("Czas w samochodzie według dni, tygodni i miesięcy", { exact: true }).click();
  await expect(page.getByRole("region", { name: "Dni", exact: true })).toContainText("45 min");
  await expect(page.getByRole("region", { name: "Tygodnie od poniedziałku", exact: true })).toContainText("10 min");
  await expect(page.getByRole("region", { name: "Miesiące", exact: true })).toContainText("2 min 3 s");
});

test("errors never render an apparent successful zero and analytics remains explicit", async ({ page }) => {
  await harness(page, "error");
  await page.goto("/");
  await expect(metric(page)).toHaveText("Błąd pobierania");
  await expect(page.getByRole("alert")).toContainText("niedostępne");
  await page.getByRole("button", { name: "Analizy", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("pobrać analiz");
  await expect(metric(page)).toHaveCount(0);
});
