CREATE TABLE IF NOT EXISTS stations (
    station_id varchar(100) PRIMARY KEY,
    name text NOT NULL,
    address text NOT NULL,
    lat double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
    zcode varchar(2) NOT NULL,
    metadata_observed_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS chargers (
    station_id varchar(100) NOT NULL REFERENCES stations(station_id),
    charger_id varchar(100) NOT NULL,
    raw_connector_type varchar(100) NOT NULL,
    output_kw double precision,
    raw_status varchar(100) NOT NULL,
    status_updated_at timestamptz,
    status_observed_at timestamptz NOT NULL,
    deleted boolean NOT NULL DEFAULT false,
    PRIMARY KEY (station_id, charger_id)
);

CREATE TABLE IF NOT EXISTS collection_state (
    task varchar(20) PRIMARY KEY,
    succeeded_at timestamptz,
    retry_after timestamptz,
    consecutive_failures integer NOT NULL DEFAULT 0
);
INSERT INTO collection_state(task) VALUES ('metadata'), ('status') ON CONFLICT DO NOTHING;

-- Existing JDBC cache installations keep the same table names, keys, and column types.
-- Counts are durable and shared by every collector instance using this database.
CREATE TABLE IF NOT EXISTS api_daily_usage (
    usage_date date PRIMARY KEY,
    calls integer NOT NULL DEFAULT 0,
    metadata_calls integer NOT NULL DEFAULT 0
);
