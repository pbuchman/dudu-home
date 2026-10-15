-- Additive, idempotent metadata migration. GPS archive tables are never rewritten.
CREATE TABLE IF NOT EXISTS routebook_metadata (
 device_id uuid PRIMARY KEY REFERENCES routebook_devices(device_id),
 metadata_revision bigint NOT NULL DEFAULT 0 CHECK(metadata_revision BETWEEN 0 AND 9007199254740991)
);
INSERT INTO routebook_metadata(device_id) SELECT device_id FROM routebook_devices ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS routebook_places (
 device_id uuid NOT NULL REFERENCES routebook_metadata(device_id),
 place_id uuid NOT NULL,
 name text NOT NULL CHECK(char_length(name) BETWEEN 1 AND 80 AND name=btrim(name)),
 lat double precision NOT NULL CHECK(lat BETWEEN -90 AND 90),
 lon double precision NOT NULL CHECK(lon BETWEEN -180 AND 180),
 radius_m double precision NOT NULL DEFAULT 100 CHECK(radius_m BETWEEN 25 AND 1000),
 PRIMARY KEY(device_id,place_id)
);
