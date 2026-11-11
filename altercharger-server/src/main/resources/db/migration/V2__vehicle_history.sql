CREATE TABLE app_users (
    user_id varchar(100) PRIMARY KEY,
    role varchar(20) NOT NULL CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE vehicles (
    vehicle_id varchar(100) PRIMARY KEY,
    user_id varchar(100) NOT NULL REFERENCES app_users(user_id)
);

INSERT INTO app_users(user_id, role) VALUES ('mock-user-001', 'USER'), ('mock-admin-001', 'ADMIN');
INSERT INTO vehicles(vehicle_id, user_id) VALUES ('mock-vehicle-001', 'mock-user-001');

CREATE TABLE charging_events (
    event_id varchar(100) CONSTRAINT pk_charging_events PRIMARY KEY,
    vehicle_id varchar(100) NOT NULL REFERENCES vehicles(vehicle_id),
    event_type varchar(20) NOT NULL CHECK (event_type IN ('START', 'COMPLETE')),
    source varchar(20) NOT NULL CHECK (source IN ('MOCK', 'REAL')),
    verified boolean NOT NULL DEFAULT false,
    occurred_at timestamptz NOT NULL,
    lat double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
    soc_percent integer NOT NULL CHECK (soc_percent BETWEEN 0 AND 100),
    odometer_km double precision CHECK (odometer_km >= 0),
    matched_station_id varchar(100),
    visit_count_added integer NOT NULL DEFAULT 0,
    daily_duplicate boolean NOT NULL DEFAULT false
);
CREATE INDEX idx_charging_events_latest ON charging_events(vehicle_id, event_type, occurred_at DESC, event_id DESC);

CREATE TABLE vehicle_snapshots (
    snapshot_id varchar(36) PRIMARY KEY,
    vehicle_id varchar(100) NOT NULL REFERENCES vehicles(vehicle_id),
    recorded_at timestamptz NOT NULL,
    soc_percent integer NOT NULL CHECK (soc_percent BETWEEN 0 AND 100),
    odometer_km double precision NOT NULL CHECK (odometer_km >= 0),
    drivable_range_km integer CHECK (drivable_range_km >= 0),
    CONSTRAINT uk_vehicle_snapshot_instant UNIQUE (vehicle_id, recorded_at)
);

CREATE TABLE station_visits (
    visit_id varchar(36) PRIMARY KEY,
    user_id varchar(100) NOT NULL REFERENCES app_users(user_id),
    station_id varchar(100) NOT NULL,
    visit_date date NOT NULL,
    source varchar(20) NOT NULL DEFAULT 'REAL' CHECK (source = 'REAL'),
    verified boolean NOT NULL DEFAULT true CHECK (verified),
    CONSTRAINT uk_real_station_visit_day UNIQUE (user_id, station_id, visit_date)
);
