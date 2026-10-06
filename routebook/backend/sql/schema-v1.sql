-- Idempotent bootstrap for a dedicated v1 database. Never resets data.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE TABLE IF NOT EXISTS routebook_devices (
 device_id uuid PRIMARY KEY,
 revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0 AND revision <= 9007199254740991)
);
CREATE TABLE IF NOT EXISTS routebook_points (
 device_id uuid NOT NULL REFERENCES routebook_devices(device_id),
 event_id uuid NOT NULL,
 segment_id uuid NOT NULL,
 measured_at timestamptz NOT NULL CHECK (measured_at >= '2000-01-01T00:00:00Z'),
 received_at timestamptz NOT NULL,
 lat double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
 lon double precision NOT NULL CHECK (lon BETWEEN -180 AND 180),
 accuracy_m double precision NOT NULL CHECK (accuracy_m > 0 AND accuracy_m <= 50),
 speed_mps double precision CHECK (speed_mps BETWEEN 0 AND 80),
 geom geometry(Point,4326) GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lon,lat),4326)) STORED,
 PRIMARY KEY (device_id,event_id)
);
CREATE INDEX IF NOT EXISTS routebook_points_measured ON routebook_points(device_id,measured_at,event_id);
CREATE INDEX IF NOT EXISTS routebook_points_segment_measured ON routebook_points(device_id,segment_id,measured_at,event_id);
CREATE OR REPLACE FUNCTION routebook_immutable_point() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'routebook points are immutable'; END $$;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname='routebook_points_immutable' AND tgrelid='routebook_points'::regclass) THEN
  CREATE TRIGGER routebook_points_immutable BEFORE UPDATE OR DELETE ON routebook_points FOR EACH ROW EXECUTE FUNCTION routebook_immutable_point();
 END IF;
END $$;
