import { defineConfig } from "vite";
import { readFileSync } from "node:fs";
// Development-only routes. Production serves runtime JSON from a private external file.
export default defineConfig({
  plugins: [
    {
      name: "private-runtime",
      configureServer(server) {
        server.middlewares.use((req, res, next) => {
          if (req.url === "/routebook-config.json") {
            res.setHeader("Content-Type", "application/json");
            res.setHeader("Cache-Control", "no-store");
            try {
              res.end(
                process.env.ROUTEBOOK_RUNTIME_CONFIG
                  ? readFileSync(process.env.ROUTEBOOK_RUNTIME_CONFIG)
                  : JSON.stringify(
                      process.env.ROUTEBOOK_DEMO === "1"
                        ? {
                            mode: "synthetic",
                            deviceId: "6b8f2886-ccbd-5b1d-b014-3fa94cc0a1ea",
                          }
                        : {},
                    ),
              );
            } catch {
              res.statusCode = 503;
              res.end("{}");
            }
            return;
          }
          if (
            req.url === "/synthetic-fixtures.json" &&
            process.env.ROUTEBOOK_DEMO === "1" &&
            process.env.ROUTEBOOK_FIXTURES_PATH
          ) {
            res.setHeader("Content-Type", "application/json");
            try {
              res.end(readFileSync(process.env.ROUTEBOOK_FIXTURES_PATH));
            } catch {
              res.statusCode = 503;
              res.end("{}");
            }
            return;
          }
          next();
        });
      },
    },
  ],
  server: { port: 5178, strictPort: true },
  build: { outDir: "dist" },
});
