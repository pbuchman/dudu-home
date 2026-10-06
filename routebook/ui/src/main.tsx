import { createRoot } from "react-dom/client";
import App from "./App";
import { apiAdapter } from "./api";
import { FixtureAdapter, type FixtureFile } from "./fixtures";
import type { RuntimeConfig } from "./types";
import "./style.css";
import "maplibre-gl/dist/maplibre-gl.css";
const root = createRoot(document.getElementById("root")!);
async function start() {
  try {
    const response = await fetch("/routebook-config.json", {
      cache: "no-store",
    });
    if (!response.ok) throw Error("Konfiguracja jest niedostępna.");
    const config: RuntimeConfig = await response.json();
    if (!config.deviceId) {
      root.render(
        <div className="configuration">
          <h1>Routebook</h1>
          <h2>Oczekiwanie na połączenie radia</h2>
          <p>Historia pojawi się po zainstalowaniu i skonfigurowaniu aplikacji na radiu.</p>
          <button onClick={() => location.reload()}>Odśwież</button>
        </div>,
      );
      return;
    }
    if (
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
        config.deviceId,
      )
    )
      throw Error(
        "Brak konfiguracji urządzenia. Uzupełnij prywatną konfigurację Routebook.",
      );
    let adapter;
    if (config.mode === "synthetic") {
      const r = await fetch("/synthetic-fixtures.json");
      if (!r.ok) throw Error("Fixtures są niedostępne.");
      const fixtures: FixtureFile = await r.json();
      if (fixtures.synthetic !== true || fixtures.device_id !== config.deviceId)
        throw Error("Niepoprawne dane demonstracyjne.");
      adapter = new FixtureAdapter(fixtures);
    } else adapter = apiAdapter(config.deviceId);
    root.render(<App adapter={adapter} config={config} />);
  } catch (e) {
    root.render(
      <div className="configuration">
        <h1>Routebook</h1>
        <h2>Konfiguracja wymaga uzupełnienia</h2>
        <p>
          {e instanceof Error
            ? e.message
            : "Nie udało się uruchomić aplikacji."}
        </p>
        <button onClick={() => location.reload()}>Spróbuj ponownie</button>
      </div>,
    );
  }
}
void start();
