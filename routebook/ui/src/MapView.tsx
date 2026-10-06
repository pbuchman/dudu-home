import { useEffect, useMemo, useRef, useState } from "react";
import type { Map as MapLibreMap, GeoJSONSource } from "maplibre-gl";
import type { RuntimeConfig, Point, ClockState } from "./types";
import type { Geometry } from "./model";
import { mapData } from "./map-data";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
export function MapIcon() {
  return (
    <svg
      width="92"
      height="92"
      viewBox="0 0 64 64"
      fill="none"
      stroke="currentColor"
      strokeWidth="3.5"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M9 17 24 9l17 9 14-8v38l-14 8-17-9-15 8Z" />
      <path d="M24 9v38M41 18v38" />
    </svg>
  );
}
export default function MapView({
  config,
  parts,
  latest,
  selectionKey,
  latestState,
}: {
  config: RuntimeConfig;
  parts: Record<string, Geometry>;
  latest: Point | null;
  selectionKey: string;
  latestState: ClockState;
}) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<MapLibreMap | null>(null);
  const [state, setState] = useState<"missing" | "loading" | "ready" | "error">(
    config.mapTilerKey ? "loading" : "missing",
  );
  const [retry, setRetry] = useState(0);
  const data = useMemo(
    () => mapData(parts, latest, latestState),
    [parts, latest, latestState],
  );
  const dataRef = useRef(data);
  dataRef.current = data;
  useEffect(() => {
    if (!config.mapTilerKey) return;
    let cancelled = false;
    let timeout: ReturnType<typeof setTimeout>;
    setState("loading");
    void import("maplibre-gl")
      .then((lib) => {
        if (cancelled || !container.current) return;
        try {
          const style = config.mapTilerStyle ?? "streets-v4";
          if (!/^[a-zA-Z0-9-]+$/.test(style)) throw Error();
          lib.setWorkerUrl(workerUrl);
          const m = new lib.Map({
            container: container.current,
            style: `https://api.maptiler.com/maps/${style}/style.json?key=${encodeURIComponent(config.mapTilerKey!)}`,
            center: [0, 0],
            zoom: 12,
            attributionControl: {
              compact: false,
              customAttribution:
                '<a href="https://www.maptiler.com/copyright/" target="_blank" rel="noopener noreferrer">© MapTiler</a> <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">© OpenStreetMap contributors</a>',
            },
          });
          map.current = m;
          m.addControl(new lib.NavigationControl(), "top-right");
          timeout = setTimeout(() => {
            if (!cancelled) setState("error");
          }, 15000);
          m.on("error", () => {
            if (!cancelled) setState("error");
          });
          m.on("load", () => {
            if (cancelled) return;
            clearTimeout(timeout);
            m.addSource("track", { type: "geojson", data: dataRef.current });
            m.addLayer({
              id: "route",
              type: "line",
              source: "track",
              filter: [
                "in",
                ["get", "kind"],
                ["literal", ["route", "boundary"]],
              ],
              paint: { "line-color": "#118b87", "line-width": 4 },
            });
            m.addLayer({
              id: "observations",
              type: "circle",
              source: "track",
              filter: ["==", ["get", "kind"], "observation"],
              paint: {
                "circle-radius": 4,
                "circle-color": "#118b87",
                "circle-stroke-color": "white",
                "circle-stroke-width": 2,
              },
            });
            m.addLayer({
              id: "stops",
              type: "circle",
              source: "track",
              filter: ["==", ["get", "kind"], "stop"],
              paint: {
                "circle-radius": 8,
                "circle-color": "white",
                "circle-stroke-color": "#d9910c",
                "circle-stroke-width": 3,
              },
            });
            m.addLayer({
              id: "latest",
              type: "circle",
              source: "track",
              filter: ["==", ["get", "kind"], "latest"],
              paint: {
                "circle-radius": 9,
                "circle-color": [
                  "case",
                  ["==", ["get", "state"], "fresh"],
                  "#118b87",
                  "#8b97a7",
                ],
                "circle-stroke-color": "white",
                "circle-stroke-width": 3,
              },
            });
            setState("ready");
          });
        } catch {
          setState("error");
        }
      })
      .catch(() => setState("error"));
    return () => {
      cancelled = true;
      clearTimeout(timeout);
      map.current?.remove();
      map.current = null;
    };
  }, [config.mapTilerKey, config.mapTilerStyle, retry]);
  useEffect(() => {
    const m = map.current;
    if (!m || state !== "ready") return;
    (m.getSource("track") as GeoJSONSource)?.setData(data);
  }, [data, state]);
  useEffect(() => {
    const m = map.current;
    if (!m || state !== "ready") return;
    const points = data.features.flatMap((f) =>
      f.geometry.type === "Point"
        ? [f.geometry.coordinates]
        : f.geometry.type === "LineString"
          ? f.geometry.coordinates
          : [],
    );
    if (!points.length) return;
    let west = Infinity,
      south = Infinity,
      east = -Infinity,
      north = -Infinity;
    for (const p of points) {
      west = Math.min(west, p[0]);
      south = Math.min(south, p[1]);
      east = Math.max(east, p[0]);
      north = Math.max(north, p[1]);
    }
    m.fitBounds(
      [
        [west, south],
        [east, north],
      ],
      { padding: 60, maxZoom: 15, duration: 0 },
    );
  }, [state, selectionKey, Object.keys(parts).length]);
  return (
    <div className="map-frame" aria-label="Mapa obserwacji GPS">
      <div className="map-canvas" ref={container} />
      {state === "ready" ? (
        <a
          className="maptiler-logo"
          href="https://www.maptiler.com"
          target="_blank"
          rel="noopener noreferrer"
        >
          <img
            src="https://api.maptiler.com/resources/logo.svg"
            alt="MapTiler logo"
            width="100"
            height="26"
          />
        </a>
      ) : null}
      {state !== "ready" ? (
        <div className="map-state" role="status">
          <MapIcon />
          <h2>{state === "loading" ? "Ładowanie mapy" : "Mapa niedostępna"}</h2>
          <p>
            {state === "missing"
              ? "Brak konfiguracji dostawcy."
              : state === "error"
                ? "Dostawca mapy jest niedostępny."
                : "Pobieranie podkładu mapy."}
            <br />
            Podsumowanie pozostaje dostępne.
          </p>
          {state === "error" ? (
            <button onClick={() => setRetry((n) => n + 1)}>
              Spróbuj ponownie
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
