-- Additive immutable radio telemetry archive. Never rewrites GPS rows or devices.
CREATE TABLE IF NOT EXISTS routebook_observations (
 device_id uuid NOT NULL REFERENCES routebook_devices(device_id),
 event_id uuid NOT NULL,
 observer_epoch uuid NOT NULL,
 sequence bigint NOT NULL CHECK(sequence BETWEEN 0 AND 9007199254740991),
 observed_at timestamptz NOT NULL CHECK(observed_at >= '2000-01-01T00:00:00Z'),
 received_at timestamptz NOT NULL,
 elapsed_realtime_ms bigint NOT NULL CHECK(elapsed_realtime_ms BETWEEN 0 AND 9007199254740991),
 boot_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('snapshot','transition','observer_lost')),
 acc_state integer,
 module1_state integer,
 interpretation_profile text NOT NULL CHECK(interpretation_profile ~ '^[A-Za-z0-9._:-]{1,80}$'),
 firmware_id text NOT NULL CHECK(firmware_id ~ '^[A-Za-z0-9._:-]{1,120}$'),
 PRIMARY KEY(device_id,event_id)
);
CREATE INDEX IF NOT EXISTS routebook_observations_time ON routebook_observations(device_id,observed_at,event_id);
CREATE INDEX IF NOT EXISTS routebook_observations_epoch ON routebook_observations(device_id,observer_epoch,sequence,event_id);
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname='routebook_observations_immutable' AND tgrelid='routebook_observations'::regclass) THEN
  CREATE TRIGGER routebook_observations_immutable BEFORE UPDATE OR DELETE ON routebook_observations FOR EACH ROW EXECUTE FUNCTION routebook_immutable_point();
 END IF;
END $$;
-- Audited IDs are write-once. Runtime removal revokes trust, without erasing evidence.
CREATE TABLE IF NOT EXISTS routebook_power_profile_audit (
 profile_id text PRIMARY KEY,
 body jsonb NOT NULL,
 registered_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname='routebook_profiles_immutable' AND tgrelid='routebook_power_profile_audit'::regclass) THEN
  CREATE TRIGGER routebook_profiles_immutable BEFORE UPDATE OR DELETE ON routebook_power_profile_audit FOR EACH ROW EXECUTE FUNCTION routebook_immutable_point();
 END IF;
END $$;
