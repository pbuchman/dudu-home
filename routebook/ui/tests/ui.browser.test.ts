import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { FixtureAdapter, type FixtureFile } from "../src/fixtures";
import type { Latest, LiveEvent } from "../src/types";
const fixturePath = process.env.ROUTEBOOK_FIXTURES_PATH;
if (!fixturePath) throw Error("ROUTEBOOK_FIXTURES_PATH required");
const fixtures: FixtureFile = JSON.parse(readFileSync(fixturePath, "utf8"));
const device = fixtures.device_id;
const fixture = new FixtureAdapter(fixtures, "gps_gap_same_source_segment");
async function demo(page: Page) {
  await page.goto("/");
  await expect(
    page.getByText("Dane syntetyczne", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Postoje ≥3 min" }),
  ).toBeVisible();
}
async function scenario(page: Page, name: string) {
  await page.getByText("Scenariusze demonstracyjne", { exact: true }).click();
  await page.getByLabel("Scenariusz").selectOption(name);
}
async function apiHarness(
  page: Page,
  {
    map = false,
    fail = false,
    slow = 0,
    wire = false,
  }: { map?: boolean; fail?: boolean; slow?: number; wire?: boolean } = {},
) {
  await page.clock.setFixedTime(new Date("2026-10-05T12:00:00.000Z"));
  const day = await fixture.day("2026-10-05", new AbortController().signal);
  const latest = await fixture.latest(new AbortController().signal);
  let active = 0,
    max = 0;
  const requests: string[] = [];
  await page.route("**/routebook-config.json", (r) =>
    r.fulfill({
      json: {
        mode: "api",
        deviceId: device,
        ...(map ? { mapTilerKey: "synthetic-browser-test" } : {}),
      },
    }),
  );
  if (map)
    await page.route("https://api.maptiler.com/**", (r) =>
      r.request().url().includes("resources/logo.svg")
        ? r.fulfill({
            contentType: "image/svg+xml",
            body: '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="26"><text x="0" y="18">MapTiler test</text></svg>',
          })
        : r.fulfill({
            json: {
              version: 8,
              sources: {},
              layers: [
                {
                  id: "background",
                  type: "background",
                  paint: { "background-color": "#e9edf2" },
                },
              ],
            },
          }),
    );
  await page.addInitScript((wire) => {
    const nativeFetch = window.fetch.bind(window);
    let active = 0,
      max = 0;
    (window as any).dayRequestMaximum = () => max;
    window.fetch = async (input, init) => {
      const isDay = String(input).startsWith("/v1/day?");
      let counted = isDay;
      const finish = () => {
        if (counted) {
          counted = false;
          active--;
        }
      };
      if (isDay) {
        active++;
        max = Math.max(max, active);
        init?.signal?.addEventListener("abort", finish, { once: true });
      }
      try {
        return await nativeFetch(input, init);
      } finally {
        finish();
        init?.signal?.removeEventListener("abort", finish);
      }
    };
    class TestEventSource extends EventTarget {
      onopen: (() => void) | null = null;
      onerror: (() => void) | null = null;
      constructor(public url: string) {
        super();
        (window as any).testSSE = this;
        setTimeout(() => this.onopen?.(), 0);
      }
      close() {}
      emit(kind: string, data: unknown) {
        this.dispatchEvent(
          new MessageEvent(kind, { data: JSON.stringify(data) }),
        );
      }
    }
    if (!wire) (window as any).EventSource = TestEventSource;
  }, wire);
  await page.route("**/v1/**", async (r) => {
    expect(r.request().headers().authorization).toBeUndefined();
    const url = new URL(r.request().url());
    if (url.pathname === "/v1/live")
      return r.fulfill({
        contentType: "text/event-stream",
        body: `retry: 100\nid: ${latest.revision}\nevent: snapshot\ndata: ${JSON.stringify({ version: 1, device_id: device, revision: latest.revision, latest, affected_dates: [] })}\n\n`,
      });
    expect(url.searchParams.get("device_id")).toBe(device);
    if (url.pathname === "/v1/latest") return r.fulfill({ json: latest });
    if (url.pathname === "/v1/range") {
      const value = await fixture.range(
        url.searchParams.get("from")!,
        url.searchParams.get("to")!,
        new AbortController().signal,
      );
      return r.fulfill({ json: value });
    }
    const date = url.searchParams.get("date")!;
    requests.push(date);
    active++;
    max = Math.max(max, active);
    await new Promise((res) => setTimeout(res, slow));
    active--;
    try {
      await r.fulfill(
        fail
          ? { status: 503, json: { code: "db_unavailable" } }
          : {
              json: {
                ...day,
                date,
                meters: date === "2026-10-05" ? 111.1949 : 0,
                point_count: date === "2026-10-05" ? 3 : 0,
                segments: date === "2026-10-05" ? day.segments : [],
                stops: [],
              },
            },
      );
    } catch {}
  });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Twój dzień" })).toBeVisible();
  if (!wire) await expect(page.locator(".connection")).toHaveText("Połączono");
  return {
    day,
    latest,
    requests,
    max: () => page.evaluate(() => (window as any).dayRequestMaximum()),
  };
}
async function emit(page: Page, event: LiveEvent, kind = "update") {
  await page.evaluate(
    ({ event, kind }) => (window as any).testSSE.emit(kind, event),
    { event, kind },
  );
}
test("desktop truthful stop, missing map, clean console, selected date survives update", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => {
    if (["error", "warning"].includes(m.type())) errors.push(m.text());
  });
  await demo(page);
  await expect(page).toHaveTitle("Routebook · prywatna historia");
  await expect(page.locator(".metrics")).toContainText("0,11");
  await expect(page.locator(".timeline-row.stop")).toContainText("3 min");
  await expect(page.getByText("Brak konfiguracji dostawcy.")).toBeVisible();
  await page.screenshot({ path: "output/desktop.png", fullPage: true });
  await page.getByLabel("Wybrany dzień").fill("2026-10-04");
  await page.getByText("Scenariusze demonstracyjne", { exact: true }).click();
  await page.getByRole("button", { name: "Aktualizacja SSE" }).click();
  await expect(page.getByLabel("Wybrany dzień")).toHaveValue("2026-10-04");
  await expect(
    page.getByText("Brak danych dla tego dnia.", { exact: false }),
  ).toBeVisible();
  expect(errors).toEqual([]);
});
test("mobile gap means missing observations, no stop, no overflow", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await demo(page);
  await scenario(page, "gps_gap_same_source_segment");
  await expect(
    page.getByText("Brak obserwacji GPS", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("Brak obserwowanych postojów.", { exact: true }),
  ).toBeVisible();
  await page.getByText("Scenariusze demonstracyjne", { exact: true }).click();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({ path: "output/mobile.png", fullPage: true });
});
test("future clock and stale change with elapsed time without SSE", async ({
  page,
}) => {
  await page.clock.install();
  await demo(page);
  await page.getByText("Scenariusze demonstracyjne", { exact: true }).click();
  await page
    .getByRole("button", { name: "Zegar w przyszłości", exact: true })
    .click();
  await expect(
    page.getByText("Ostrzeżenie zegara", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Na żywo", exact: true }).click();
  await expect(page.locator(".position-strip")).not.toContainText("Na żywo");
  await page.screenshot({ path: "output/future-clock.png", fullPage: true });
  await page.clock.fastForward(61000);
  await expect(page.locator(".position-strip")).toContainText("Na żywo");
  await page.clock.fastForward(31000);
  await expect(page.locator(".position-strip")).toContainText(
    "Ostatnio widziany",
  );
});
test("range summary includes zero days and partial month; map progress completes", async ({
  page,
}) => {
  await demo(page);
  await page.getByRole("button", { name: "Zakres", exact: true }).click();
  await expect(page.locator(".range-progress")).toContainText(
    "2/2 dni · pobrano wszystkie dni",
  );
  await expect(
    page.getByText("Część miesiąca w wybranym zakresie"),
  ).toBeVisible();
  await page.getByText("Dane dzienne (2)", { exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(2);
  await page.screenshot({ path: "output/range.png", fullPage: true });
  await page.getByLabel("Od", { exact: true }).fill("2026-10-06");
  await page.getByRole("button", { name: "Pokaż zakres" }).click();
  await expect(page.getByRole("alert")).toContainText("poprawny zakres");
});
test("real adapter applies snapshot/update, rejects lower revision, refetches skip and reconnect", async ({
  page,
}) => {
  const h = await apiHarness(page);
  await expect.poll(() => h.requests.length).toBeGreaterThan(0);
  const l = h.latest;
  const event = (
    revision: number,
    affected_dates: string[],
    point = l.point,
  ): LiveEvent => ({
    version: 1,
    device_id: device,
    revision,
    latest: { ...l, revision, point },
    affected_dates,
  });
  const count = h.requests.length;
  await emit(page, event(5, []), "snapshot");
  await expect.poll(() => h.requests.length).toBeGreaterThan(count);
  await page.getByLabel("Wybrany dzień").fill("2026-10-04");
  await expect.poll(() => h.requests.at(-1)).toBe("2026-10-04");
  await emit(
    page,
    event(4, ["2026-10-04"], {
      ...l.point!,
      measured_at: "2026-10-05T07:00:00.000Z",
    }),
  );
  await expect(page.locator(".position-strip")).toContainText("10:01:00");
  const before = h.requests.length;
  await emit(page, event(8, []));
  await expect.poll(() => h.requests.length).toBeGreaterThan(before);
  await page.evaluate(() => (window as any).testSSE.onerror());
  await expect(page.locator(".connection")).toContainText("Offline");
  await page.evaluate(() => (window as any).testSSE.onopen());
  const reconnect = h.requests.length;
  await emit(page, event(8, []), "snapshot");
  await expect.poll(() => h.requests.length).toBeGreaterThan(reconnect);
  await expect(page.getByLabel("Wybrany dzień")).toHaveValue("2026-10-04");
});
test("range selection cancels old work and enforces max two API requests", async ({
  page,
}) => {
  const h = await apiHarness(page, { slow: 250 });
  await expect.poll(() => h.requests.length).toBe(1);
  await page.getByRole("button", { name: "Zakres", exact: true }).click();
  await expect(page.locator(".range-progress")).toContainText(
    "pobrano wszystkie dni",
  );
  await page.getByLabel("Od", { exact: true }).fill("2026-09-01");
  await page.getByRole("button", { name: "Pokaż zakres" }).click();
  await expect(
    page.getByRole("button", { name: "Anuluj pobieranie" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Anuluj pobieranie" }).click();
  await expect(page.locator(".range-progress")).toContainText("anulowano");
  await page.getByLabel("Od", { exact: true }).fill("2026-10-05");
  await page.getByRole("button", { name: "Pokaż zakres" }).click();
  await expect(page.locator(".range-progress")).toContainText(
    "1/1 dni · pobrano wszystkie dni",
  );
  expect(await h.max()).toBeLessThanOrEqual(2);
  await expect(page.locator(".metrics")).toContainText("0,11");
});
test("API unavailable surfaces actionable error and summaries survive provider failure", async ({
  page,
}) => {
  await apiHarness(page, { fail: true });
  await expect(page.getByRole("alert")).toContainText("chwilowo niedostępne");
  await expect(
    page.getByRole("button", { name: "Ponów pobranie" }),
  ).toBeVisible();
  await page.screenshot({ path: "output/api-error.png", fullPage: true });
});
test("actual MapLibre renders synthetic geometry with mocked style; no provider runtime claim", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => {
    if (m.type() === "error") errors.push(m.text());
  });
  await apiHarness(page, { map: true });
  await expect(page.locator(".metrics")).toContainText("0,11");
  await expect(page.locator(".map-state"))
    .toHaveCount(0)
    .catch((e) => {
      throw Error(String(e) + "\n" + errors.join("\n"));
    });
  await expect(page.locator("canvas")).toBeVisible();
  expect(
    await page
      .locator("canvas")
      .evaluate((c) => c.getBoundingClientRect().height),
  ).toBeGreaterThan(400);
  await page.waitForTimeout(600);
  await page.screenshot({
    path: process.env.ROUTEBOOK_TEST_URL
      ? "output/maplibre-production-synthetic.png"
      : "output/maplibre-synthetic.png",
    fullPage: true,
  });
  expect(errors).toEqual([]);
});

test("native EventSource parses wire snapshots and refetches after automatic reconnect", async ({
  page,
}) => {
  const h = await apiHarness(page, { wire: true });
  await expect.poll(() => h.requests.length).toBeGreaterThan(1);
  await expect(page.locator(".position-strip")).toContainText("10:01:00");
});
test("map provider failure preserves API summary", async ({ page }) => {
  await page.route("https://api.maptiler.com/**", (r) =>
    r.fulfill({ status: 503, json: { code: "unavailable" } }),
  );
  await apiHarness(page);
  await page.route("**/routebook-config.json", (r) =>
    r.fulfill({
      json: {
        mode: "api",
        deviceId: device,
        mapTilerKey: "synthetic-error-test",
      },
    }),
  );
  await page.reload();
  await expect(page.locator(".metrics")).toContainText("0,11");
  await expect(
    page.getByText("Dostawca mapy jest niedostępny.", { exact: false }),
  ).toBeVisible();
  await page.screenshot({ path: "output/provider-error.png", fullPage: true });
});

test("all normative UI fixture scenes render their accepted observations", async ({
  page,
}) => {
  await demo(page);
  await page.getByText("Scenariusze demonstracyjne", { exact: true }).click();
  for (const c of fixtures.cases.filter(
    (c) => c.name !== "warsaw_dst_day_bounds",
  )) {
    await page.getByLabel("Scenariusz").selectOption(c.name);
    const a = new FixtureAdapter(fixtures, c.name);
    const d = await a.day("2026-10-05", new AbortController().signal);
    await expect(
      page.locator(".metrics>div").nth(1).locator("strong"),
    ).toHaveText(String(d.point_count));
    if (c.name === "future_clock_not_live")
      await expect(page.locator(".position-strip")).toContainText(
        "Ostrzeżenie zegara",
      );
  }
});

test("range day failures are labelled partial while range totals remain available", async ({
  page,
}) => {
  await apiHarness(page, { fail: true });
  await page.getByRole("button", { name: "Zakres", exact: true }).click();
  await expect(page.locator(".range-progress")).toContainText(
    "2/2 dni · mapa częściowa",
  );
  await expect(page.locator(".range-progress")).toContainText(
    "Nie pobrano: 2026-10-04, 2026-10-05",
  );
  await expect(page.locator(".metrics")).toContainText("0,11");
  await page.screenshot({ path: "output/range-partial.png", fullPage: true });
});
test("changing selection aborts old range without accepting delayed results", async ({
  page,
}) => {
  const h = await apiHarness(page, { slow: 220 });
  await expect(page.locator(".metrics")).toContainText("0,11");
  await page.getByRole("button", { name: "Zakres", exact: true }).click();
  await expect(page.locator(".range-progress")).toContainText(
    "pobrano wszystkie dni",
  );
  await page.getByLabel("Od", { exact: true }).fill("2026-09-01");
  await page.getByRole("button", { name: "Pokaż zakres" }).click();
  await expect
    .poll(() => h.requests.filter((d) => d.startsWith("2026-09")).length)
    .toBe(2);
  await page.getByLabel("Od", { exact: true }).fill("2026-10-05");
  await page.getByRole("button", { name: "Pokaż zakres" }).click();
  await expect(page.locator(".range-progress")).toContainText(
    "1/1 dni · pobrano wszystkie dni",
  );
  await page.waitForTimeout(250);
  expect(h.requests.filter((d) => d.startsWith("2026-09"))).toEqual([
    "2026-09-01",
    "2026-09-02",
  ]);
  expect(await h.max()).toBeLessThanOrEqual(2);
  await expect(page.locator(".range-progress")).toContainText("1/1 dni");
  await expect(page.locator(".metrics")).toContainText("0,11");
});
