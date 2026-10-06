import type {
  Feature,
  FeatureCollection,
  Geometry as GeoGeometry,
} from "geojson";
import type { Geometry } from "./model";
import type { Point, ClockState } from "./types";
export function mapData(
  parts: Record<string, Geometry>,
  latest: Point | null,
  state: ClockState = "stale",
): FeatureCollection {
  const features: Feature<GeoGeometry>[] = [];
  const line = (points: Point[], kind: string, id: string) => {
    if (points.length > 1)
      features.push({
        type: "Feature",
        properties: { kind, id },
        geometry: {
          type: "LineString",
          coordinates: points.map((p) => [p.lon, p.lat]),
        },
      });
  };
  const stops = new Set<string>();
  for (const [date, data] of Object.entries(parts))
    for (const s of data.segments) {
      line(s.points, "route", date + ":" + s.run_id);
      if (s.edge_from && s.points[0])
        line([s.edge_from, s.points[0]], "boundary", date + ":" + s.run_id);
      for (const p of [s.points[0], s.points.at(-1)].filter(Boolean) as Point[])
        features.push({
          type: "Feature",
          properties: { kind: "observation" },
          geometry: { type: "Point", coordinates: [p.lon, p.lat] },
        });
    }
  for (const data of Object.values(parts))
    for (const s of data.stops)
      if (!stops.has(s.stop_id)) {
        stops.add(s.stop_id);
        features.push({
          type: "Feature",
          properties: { kind: "stop", id: s.stop_id },
          geometry: { type: "Point", coordinates: [s.lon, s.lat] },
        });
      }
  if (latest && state !== "future_clock")
    features.push({
      type: "Feature",
      properties: { kind: "latest", state },
      geometry: { type: "Point", coordinates: [latest.lon, latest.lat] },
    });
  return { type: "FeatureCollection", features };
}
