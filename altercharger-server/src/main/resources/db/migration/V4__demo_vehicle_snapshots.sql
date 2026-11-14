-- One-time synthetic driving history for mock-vehicle-001, managed by Flyway.
-- Use the 14 completed KST dates before migration day; never create future samples.
-- Five samples (00:30, 06:15, 12:00, 17:45, 23:30) cover each day with gaps below six hours.
WITH seed_days AS (
    SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date - 14 + day_index AS record_date,
           day_index
    FROM generate_series(0, 13) AS days(day_index)
)
INSERT INTO vehicle_snapshots (
    snapshot_id, vehicle_id, recorded_at, soc_percent, odometer_km, drivable_range_km
)
SELECT md5('demo-driving-v4:' || vehicle.vehicle_id || ':' || days.record_date::text
           || ':' || samples.sample_index::text)::uuid::text,
       vehicle.vehicle_id,
       (days.record_date + time '00:30' + samples.sample_index * interval '5 hours 45 minutes')
           AT TIME ZONE 'Asia/Seoul',
       85 - (days.day_index % 4) * 12 - samples.sample_index * 2,
       20000.0 + days.day_index * 40 + samples.sample_index * 10,
       NULL
FROM seed_days AS days
CROSS JOIN generate_series(0, 4) AS samples(sample_index)
JOIN vehicles AS vehicle ON vehicle.vehicle_id = 'mock-vehicle-001'
-- Preserve existing measurements, including their IDs and values.
ON CONFLICT (vehicle_id, recorded_at) DO NOTHING;
