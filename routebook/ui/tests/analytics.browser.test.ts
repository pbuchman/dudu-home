import { test, expect, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { analyticsFixture, device } from "./analytics-fixture";
const evidence =
  process.env.ROUTEBOOK_UI_EVIDENCE_DIR ?? "/tmp/dudu-routebook-14-ui";
mkdirSync(evidence, { recursive: true });
async function harness(
  page: Page,
  { map = false, empty = false, slow = 0, multiple = false } = {},
) {
  const fixtures = analyticsFixture();
  if (multiple)
    fixtures.summary.journeys.push({
      ...fixtures.summary.journeys[0],
      journey_id: "40000000-0000-4000-8000-000000000002",
      end: {
        ...fixtures.summary.journeys[0].end,
        place: { ...fixtures.summary.places[1], name: "Miejsce Gamma" },
      },
    });
  let sourceRevision = 1,
    metadataRevision = 0,
    summaryRequests = 0;
  const writes: { method: string; body: any; header: string | undefined }[] =
    [];
  const dates: { from: string | null; to: string | null }[] = [];
  await page.clock.setFixedTime(new Date("2026-10-05T12:00:00Z"));
  await page.addInitScript(() => {
    class Source extends EventTarget {
      onopen: (() => void) | null = null;
      constructor() {
        super();
        (window as any).analyticsSSE = this;
        setTimeout(() => this.onopen?.(), 0);
      }
      close() {}
    }
    (window as any).EventSource = Source;
  });
  await page.route("**/routebook-config.json", (route) =>
    route.fulfill({
      json: {
        mode: "api",
        deviceId: device,
        ...(map ? { mapTilerKey: "synthetic-ui-test" } : {}),
      },
    }),
  );
  if (map)
    await page.route("https://api.maptiler.com/**", (r) =>
      r.request().url().includes("logo.svg")
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
                  paint: { "background-color": "#edf2f4" },
                },
              ],
            },
          }),
    );
  const latest = () => ({
    version: 1,
    device_id: device,
    revision: sourceRevision,
    server_time: "2026-10-05T12:00:00Z",
    point: fixtures.detail.points.at(-1),
    fresh: false,
    clock_state: "stale",
  });
  await page.route("**/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    if (url.pathname.startsWith("/v1/places")) {
      const body = route.request().postDataJSON();
      writes.push({
        method,
        body,
        header: route.request().headers()["x-routebook-write"],
      });
      if (body.expected_metadata_revision !== metadataRevision)
        return route.fulfill({
          status: 409,
          json: { code: "metadata_conflict" },
        });
      const id = url.pathname.split("/")[3];
      if (method === "DELETE")
        fixtures.summary.places = fixtures.summary.places.filter(
          (p) => p.place_id !== id,
        );
      else {
        const place = {
          place_id: id ?? "60000000-0000-4000-8000-000000000001",
          name: body.name,
          lat: body.lat,
          lon: body.lon,
          radius_m: body.radius_m,
        };
        fixtures.summary.places = [
          ...fixtures.summary.places.filter((p) => p.place_id !== id),
          place,
        ];
      }
      metadataRevision++;
      return route.fulfill({
        json: {
          version: 1,
          device_id: device,
          metadata_revision: metadataRevision,
          places: fixtures.summary.places,
        },
      });
    }
    if (url.pathname === "/v1/latest") return route.fulfill({ json: latest() });
    if (url.pathname === "/v1/day")
      return route.fulfill({
        json: {
          version: 1,
          device_id: device,
          revision: sourceRevision,
          date: url.searchParams.get("date"),
          timezone: "Europe/Warsaw",
          derivation: {},
          meters: 0,
          point_count: 0,
          segments: [],
          stops: [],
        },
      });
    if (url.pathname === "/v1/analytics") {
      summaryRequests++;
      dates.push({
        from: url.searchParams.get("from"),
        to: url.searchParams.get("to"),
      });
      return route.fulfill({
        json: {
          ...fixtures.summary,
          source_revision: sourceRevision,
          metadata_revision: metadataRevision,
          ...(empty
            ? { journeys: [], visits: [], places: [], comparisons: [] }
            : {}),
        },
      });
    }
    if (url.pathname === "/v1/analytics/journey") {
      const captured = sourceRevision;
      if (slow) await new Promise((resolve) => setTimeout(resolve, slow));
      return route
        .fulfill({
          json: {
            ...fixtures.detail,
            journey: fixtures.summary.journeys.find(
              (j) => j.journey_id === url.searchParams.get("journey_id"),
            )!,
            source_revision: captured,
            metadata_revision: metadataRevision,
          },
        })
        .catch(() => {});
    }
    return route.fulfill({ status: 404, json: { code: "not_found" } });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Analizy", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Dziennik podróży" }),
  ).toBeVisible();
  if (!empty)
    await expect(
      page.getByRole("slider", { name: "Kursor wykresu prędkości" }),
    ).toBeVisible();
  return {
    writes,
    dates,
    requests: () => summaryRequests,
    update: async () => {
      sourceRevision++;
      await page.evaluate(
        (value) =>
          (window as any).analyticsSSE.dispatchEvent(
            new MessageEvent("update", { data: JSON.stringify(value) }),
          ),
        {
          version: 1,
          device_id: device,
          revision: sourceRevision,
          latest: latest(),
          affected_dates: [],
        },
      );
    },
  };
}
test("analytics desktop: diary, historical marker, shared keyboard cursor and replay gap", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => {
    if (m.type() === "error") errors.push(m.text());
  });
  await harness(page, { map: true });
  await expect(page).toHaveTitle("Routebook · prywatna historia");
  await expect(page.locator(".journey-detail canvas")).toBeVisible();
  const slider = page.getByRole("slider", { name: "Kursor wykresu prędkości" });
  await slider.focus();
  await page.keyboard.press("ArrowRight");
  await expect(
    page.getByRole("slider", { name: "Pozycja w podróży" }),
  ).toHaveValue("1");
  await expect(page.locator(".historical-label")).toContainText("10:00:10");
  await page.getByLabel("Tempo", { exact: true }).selectOption("120");
  await page
    .getByRole("button", { name: "Odtwórz podróż", exact: true })
    .click();
  await expect(page.locator(".replay-note")).toContainText(
    "Pominięto przerwę obserwacji: 1 min",
  );
  await expect(
    page.getByRole("button", { name: "Odtwórz podróż", exact: true }),
  ).toBeVisible();
  await page.getByRole("slider", { name: "Pozycja w podróży" }).fill("4");
  await expect(page.locator(".cursor-readout")).toContainText("Brak danych");
  await expect(page.locator(".journey-detail")).toContainText("72 km/h");
  await expect(page.locator(".journey-detail")).not.toContainText("3600 km/h");
  await page.getByText("Godzina wyjazdu · liczebność próbki").click();
  await expect(page.locator(".comparison tbody")).toContainText("10:00");
  await page.screenshot({
    path: `${evidence}/analytics-desktop.png`,
    fullPage: true,
  });
  expect(errors).toEqual([]);
});
test("analytics mobile place CRUD survives reload; names render as text", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const h = await harness(page);
  await page
    .getByRole("button", { name: "Nazwij miejsce", exact: true })
    .click();
  const form = page.locator(".place-form");
  await form
    .getByLabel("Nazwa", { exact: true })
    .fill('<img src=x onerror="window.xss=1">');
  await form
    .getByRole("button", { name: "Zapisz miejsce", exact: true })
    .click();
  await expect(page.locator(".places-list")).toContainText(
    '<img src=x onerror="window.xss=1">',
  );
  expect(await page.evaluate(() => (window as any).xss)).toBeUndefined();
  expect(h.writes[0]).toMatchObject({
    method: "POST",
    header: "1",
    body: { lat: 0, lon: 0.01, expected_metadata_revision: 0 },
  });
  await page
    .getByRole("button", {
      name: 'Edytuj <img src=x onerror="window.xss=1">',
      exact: true,
    })
    .click();
  await form.getByLabel("Nazwa", { exact: true }).fill("Miejsce Gamma");
  await form
    .getByRole("button", { name: "Zapisz miejsce", exact: true })
    .click();
  await expect(page.locator(".places-list")).toContainText("Miejsce Gamma");
  await page.reload();
  await page.getByRole("button", { name: "Analizy", exact: true }).click();
  await expect(page.locator(".places-list")).toContainText("Miejsce Gamma");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `${evidence}/analytics-mobile.png`,
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Usuń Miejsce Gamma", exact: true })
    .click();
  await page.getByRole("button", { name: "Potwierdź usunięcie" }).click();
  await expect(page.locator(".places-list")).not.toContainText("Miejsce Gamma");
  expect(h.writes.map((w) => w.method)).toEqual(["POST", "PUT", "DELETE"]);
});
test("backfill refreshes analytics and stops replay; periods send exact boundaries", async ({
  page,
}) => {
  const h = await harness(page, { slow: 150 });
  await page
    .getByRole("button", { name: "Odtwórz podróż", exact: true })
    .click();
  const before = h.requests();
  await h.update();
  await expect.poll(h.requests).toBeGreaterThan(before);
  await expect(
    page.getByRole("button", { name: "Odtwórz podróż", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("slider", { name: "Pozycja w podróży" }),
  ).toHaveValue("0");
  await page.getByRole("button", { name: "Tydzień", exact: true }).click();
  await expect
    .poll(() => h.dates.at(-1))
    .toEqual({ from: "2026-10-05", to: "2026-10-11" });
  await page.getByRole("button", { name: "Miesiąc", exact: true }).click();
  await expect
    .poll(() => h.dates.at(-1))
    .toEqual({ from: "2026-10-01", to: "2026-10-31" });
  await page
    .getByRole("button", { name: "Dzień", exact: true })
    .first()
    .click();
  await expect(
    page.getByRole("heading", { name: "Twój dzień", exact: true }),
  ).toBeVisible();
});
test("empty period displays missing statistics and allows place creation", async ({
  page,
}) => {
  await harness(page, { empty: true });
  await expect(page.locator(".period-card .analytics-metrics")).toContainText(
    "Brak danych",
  );
  await expect(
    page.locator(".period-card .analytics-metrics"),
  ).not.toContainText("0,00");
  await expect(
    page.getByText("Brak podróży w tym okresie.", { exact: false }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Dodaj miejsce" }).click();
  await expect(
    page.getByRole("heading", { name: "Nowe miejsce" }),
  ).toBeVisible();
});

test("changing journey aborts delayed detail and keeps the newly selected route", async ({
  page,
}) => {
  await harness(page, { multiple: true, slow: 300 });
  const rows = page.locator(".journey-row");
  await rows.nth(1).click();
  await rows.nth(0).click();
  await rows.nth(1).click();
  await expect(page.locator(".journey-detail > .section-title h2")).toHaveText(
    "Miejsce Alfa → Miejsce Gamma",
  );
  await expect(rows.nth(1)).toHaveAttribute("aria-pressed", "true");
  await page.waitForTimeout(400);
  await expect(page.locator(".journey-detail > .section-title h2")).toHaveText(
    "Miejsce Alfa → Miejsce Gamma",
  );
});

test("touch chart and slider select the shared historical observation", async ({
  browser,
}) => {
  const context = await browser.newContext({
    baseURL: process.env.ROUTEBOOK_TEST_URL ?? "http://127.0.0.1:5178",
    viewport: { width: 390, height: 844 },
    hasTouch: true,
  });
  const page = await context.newPage();
  try {
    await harness(page);
    const chart = page.getByRole("slider", {
      name: "Kursor wykresu prędkości",
    });
    await chart.tap({ position: { x: 260, y: 70 } });
    await expect(
      page.getByRole("slider", { name: "Pozycja w podróży" }),
    ).not.toHaveValue("0");
    await expect(page.locator(".historical-label")).toContainText("10:01:");
    await expect(
      page.getByRole("button", { name: "Odtwórz podróż", exact: true }),
    ).toBeVisible();
  } finally {
    await context.close();
  }
});

test("analytics busy retries reads within bounds; metadata conflict retains the draft", async ({
  page,
}) => {
  await harness(page);
  let busyRequests = 0;
  await page.route("**/v1/analytics?**", (r) => {
    busyRequests++;
    return r.fulfill({
      status: 429,
      headers: { "Retry-After": "1" },
      json: { code: "analytics_busy" },
    });
  });
  await page
    .getByRole("button", { name: "Odśwież analizy", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("Analizy są zajęte", {
    timeout: 10000,
  });
  expect(busyRequests).toBe(3);
  await page.unroute("**/v1/analytics?**");
  await page
    .getByRole("button", { name: "Odśwież analizy", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Nazwij miejsce", exact: true })
    .click();
  await page
    .locator(".place-form")
    .getByLabel("Nazwa", { exact: true })
    .fill("Zachowaj szkic");
  let writeRequests = 0;
  await page.route("**/v1/places?**", (r) => {
    writeRequests++;
    return r.fulfill({ status: 409, json: { code: "metadata_conflict" } });
  });
  await page
    .getByRole("button", { name: "Zapisz miejsce", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText(
    "Miejsca zmieniły się w innej karcie",
  );
  await expect(
    page.locator(".place-form").getByLabel("Nazwa", { exact: true }),
  ).toHaveValue("Zachowaj szkic");
  expect(writeRequests).toBe(1);
});
