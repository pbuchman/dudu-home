import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "tests",
  testMatch: "**/*.browser.test.ts",
  fullyParallel: false,
  workers: 1,
  timeout: 30000,
  reporter: [
    ["list"],
    [
      "json",
      {
        outputFile: process.env.ROUTEBOOK_TEST_URL
          ? "output/browser-production-results.json"
          : "output/browser-results.json",
      },
    ],
  ],
  outputDir: "output/playwright",
  use: {
    baseURL: process.env.ROUTEBOOK_TEST_URL ?? "http://127.0.0.1:5178",
    viewport: { width: 1586, height: 992 },
    screenshot: "only-on-failure",
    launchOptions: {
      args: [
        "--enable-webgl",
        "--use-gl=angle",
        "--use-angle=swiftshader",
        "--enable-unsafe-swiftshader",
      ],
    },
  },
  webServer: process.env.ROUTEBOOK_TEST_URL
    ? undefined
    : {
        command: "npm run dev --workspaces=false",
        env: { ROUTEBOOK_DEMO: "1" },
        url: "http://127.0.0.1:5178",
        reuseExistingServer: true,
      },
});
