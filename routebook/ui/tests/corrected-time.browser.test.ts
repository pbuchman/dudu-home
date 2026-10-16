import { test, expect, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { analyticsFixture, device } from "./analytics-fixture";
const evidence =
  process.env.ROUTEBOOK_UI_EVIDENCE_DIR ?? "/tmp/dudu-parking-correction-ui";
mkdirSync(evidence, { recursive: true });
async function harness(page: Page, maturity = false) {
  const fixtures = analyticsFixture(),
    initial = Date.parse("2026-10-05T12:00:00Z");
  if (maturity) await page.clock.install({ time: initial });
  else await page.clock.setFixedTime(new Date(initial));
  let stage = 0,
    revision = 1;
  const requests = { day: 0, analytics: 0 };
  const metric = () => ({
    recorded_seconds: stage === 1 ? 3000 : 2700,
    in_car_seconds:
      stage === 1 ? 3000 : stage === 2 ? 2100 : stage === 3 ? 1700 : 1800,
    excluded_tail_seconds:
      stage === 1 ? 0 : stage === 2 ? 600 : stage === 3 ? 1000 : 900,
    confirmed_excluded_seconds: stage === 2 ? 600 : 0,
    estimated_excluded_seconds:
      stage === 1 || stage === 2 ? 0 : stage === 3 ? 1000 : 900,
    provisional_excluded_seconds: stage === 0 ? 300 : 0,
    correction_algorithm_version: "routebook-in-car-1",
    correction_computed_at: new Date(initial).toISOString(),
    correction_next_refresh_at:
      maturity && stage === 0 ? new Date(initial + 60000).toISOString() : null,
  });
  await page.addInitScript(() => {
    class Source extends EventTarget {
      onopen: (() => void) | null = null;
      constructor() {
        super();
        (window as any).parkingSSE = this;
        setTimeout(() => this.onopen?.(), 0);
      }
      close() {}
    }
    (window as any).EventSource = Source;
  });
  const latest = () => ({
    version: 1,
    device_id: device,
    revision,
    server_time: new Date(initial).toISOString(),
    point: fixtures.detail.points.at(-1),
    fresh: false,
    clock_state: "stale",
  });
  await page.route("**/routebook-config.json", (r) =>
    r.fulfill({ json: { mode: "api", deviceId: device } }),
  );
  await page.route("**/v1/**", async (r) => {
    const url = new URL(r.request().url()),
      correction = metric();
    const row = {
      date: "2026-10-05",
      point_count: 100,
      stop_count: 1,
      meters: 800,
      ...correction,
    };
    const base = {
      version: 1,
      device_id: device,
      revision,
      timezone: "Europe/Warsaw",
      derivation: { stop_seconds: 180 },
      meters: 800,
      ...correction,
    };
    if (url.pathname === "/v1/latest") return r.fulfill({ json: latest() });
    if (url.pathname === "/v1/day") {
      requests.day++;
      return r.fulfill({
        json: {
          ...base,
          date: url.searchParams.get("date"),
          point_count: 100,
          segments: [],
          stops: [],
        },
      });
    }
    if (url.pathname === "/v1/range")
      return r.fulfill({
        json: {
          ...base,
          from: url.searchParams.get("from"),
          to: url.searchParams.get("to"),
          days: [row],
          months: [{ month: "2026-10", meters: 800, ...correction }],
        },
      });
    if (url.pathname === "/v1/analytics") {
      requests.analytics++;
      const period = {
        ...fixtures.summary.days[0],
        point_count: 100,
        ...correction,
      };
      return r.fulfill({
        json: {
          ...fixtures.summary,
          ...correction,
          source_revision: revision,
          totals: {
            ...fixtures.summary.totals,
            point_count: 100,
            ...correction,
          },
          journeys: fixtures.summary.journeys.map((j) => ({
            ...j,
            ...correction,
          })),
          days: [period],
          weeks: [period],
          months: [period],
          comparisons: fixtures.summary.comparisons.map((c) => ({
            ...c,
            ...correction,
            mean_recorded_seconds: 2700,
            median_recorded_seconds: 2700,
            mean_in_car_seconds: correction.in_car_seconds,
            median_in_car_seconds: correction.in_car_seconds,
            mean_excluded_tail_seconds: correction.excluded_tail_seconds,
            median_excluded_tail_seconds: correction.excluded_tail_seconds,
            departures: c.departures.map((d) => ({
              ...d,
              mean_in_car_seconds: correction.in_car_seconds,
              median_in_car_seconds: correction.in_car_seconds,
            })),
          })),
        },
      });
    }
    if (url.pathname === "/v1/analytics/journey")
      return r.fulfill({
        json: {
          ...fixtures.detail,
          ...correction,
          source_revision: revision,
          journey: { ...fixtures.detail.journey, ...correction },
          correction_intervals: [
            {
              start_at: "2026-10-05T08:01:00Z",
              end_at: "2026-10-05T08:01:40Z",
              reason:
                stage === 2
                  ? "confirmed_power_off"
                  : "provisional_archive_tail",
            },
          ],
        },
      });
    return r.fulfill({ status: 404 });
  });
  return {
    requests,
    mature: () => {
      stage = 3;
    },
    update: async (next: number) => {
      stage = next;
      revision++;
      await page.evaluate(
        (value) =>
          (window as any).parkingSSE.dispatchEvent(
            new MessageEvent("update", { data: JSON.stringify(value) }),
          ),
        {
          version: 1,
          device_id: device,
          revision,
          latest: latest(),
          affected_dates: ["2026-10-05"],
        },
      );
    },
  };
}
const primary = (page: Page) => page.locator(".recorded-metric dd").first();
test("corrected day, range/table, analytics/detail/directions and late signal updates", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => {
    if (["error", "warning"].includes(m.type())) errors.push(m.text());
  });
  const h = await harness(page);
  await page.goto("/");
  await expect(page).toHaveTitle(/Routebook/);
  await expect(page.locator("vite-error-overlay")).toHaveCount(0);
  await expect(primary(page)).toHaveText("30 min");
  await expect(page.locator(".summary")).toContainText(
    "Czas szacowany · tymczasowy",
  );
  await page.getByText("Zapis GPS i korekta", { exact: true }).click();
  for (const text of ["45 min", "15 min", "5 min"])
    await expect(page.locator(".correction-detail")).toContainText(text);
  await page.screenshot({
    path: `${evidence}/corrected-day-desktop.png`,
    fullPage: true,
  });
  await page.getByRole("button", { name: "Zakres", exact: true }).click();
  await page.getByLabel("Od", { exact: true }).fill("2026-10-05");
  await page.getByLabel("Do", { exact: true }).fill("2026-10-05");
  await page.getByRole("button", { name: "Pokaż zakres" }).click();
  await expect(primary(page)).toHaveText("30 min");
  await page.getByText("Dane dzienne (1)", { exact: true }).click();
  await expect(page.getByRole("table")).toContainText("30 min");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `${evidence}/corrected-range-mobile.png`,
    fullPage: true,
  });
  await page.getByRole("button", { name: "Analizy", exact: true }).click();
  await expect(primary(page)).toHaveText("30 min");
  await expect(page.locator(".journey-detail .recorded-metric")).toContainText(
    "30 min",
  );
  for (const text of ["Mediana czasu w samochodzie", "30 min", "45 min"])
    await expect(page.locator(".comparison")).toContainText(text);
  await page
    .getByText("Czas w samochodzie według dni, tygodni i miesięcy", {
      exact: true,
    })
    .click();
  for (const name of ["Dni", "Tygodnie od poniedziałku", "Miesiące"])
    await expect(page.getByRole("region", { name, exact: true })).toContainText(
      "30 min",
    );
  await page.getByText("Przedziały wykluczenia", { exact: true }).click();
  await expect(page.locator(".journey-detail")).toContainText(
    "szacowany postój końcowy · tymczasowy",
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `${evidence}/corrected-analytics-mobile.png`,
    fullPage: true,
  });
  await page
    .locator(".comparison")
    .screenshot({ path: `${evidence}/corrected-comparison-mobile.png` });
  await page.setViewportSize({ width: 1586, height: 992 });
  await page.screenshot({
    path: `${evidence}/corrected-analytics-desktop.png`,
    fullPage: true,
  });
  await h.update(1); // Backlog undoes inferred parking without changing the latest fix.
  await expect(primary(page)).toHaveText("50 min");
  await expect(page.locator(".journey-detail .recorded-metric")).toContainText(
    "50 min",
  );
  await h.update(2); // Power-only update, same latest GPS point.
  await expect(primary(page)).toHaveText("35 min");
  await expect(page.locator(".period-card")).toContainText(
    "Korekta potwierdzona sygnałem zasilania",
  );
  await expect(page.locator(".journey-detail .recorded-metric")).toContainText(
    "35 min",
  );
  await page
    .getByRole("button", { name: "Dzień", exact: true })
    .first()
    .click();
  await expect(primary(page)).toHaveText("35 min");
  expect(errors).toEqual([]);
});
for (const view of ["day", "range", "analytics"] as const)
  test(`clock-only maturity refreshes ${view}`, async ({ page }) => {
    const h = await harness(page, true);
    await page.goto("/");
    if (view === "analytics")
      await page.getByRole("button", { name: "Analizy", exact: true }).click();
    if (view === "range")
      await page.getByRole("button", { name: "Zakres", exact: true }).click();
    await expect(primary(page)).toHaveText("30 min");
    const before = view === "analytics" ? h.requests.analytics : h.requests.day;
    h.mature();
    await page.clock.runFor(61000);
    await expect(primary(page)).toHaveText("28 min 20 s");
    expect(
      view === "analytics" ? h.requests.analytics : h.requests.day,
    ).toBeGreaterThan(before);
  });

test("a repeated stale correction deadline does not loop requests", async ({
  page,
}) => {
  const h = await harness(page, true);
  await page.goto("/");
  await expect(primary(page)).toHaveText("30 min");
  await page.clock.runFor(61000);
  await expect.poll(() => h.requests.day).toBe(2);
  await page.clock.runFor(10000);
  expect(h.requests.day).toBe(2);
});
