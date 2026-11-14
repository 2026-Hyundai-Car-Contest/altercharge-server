-- Keep the shared demo vehicle's historical measurements and matching MOCK completions current.
-- V4 remains immutable. This function extends its deterministic IDs and mileage/SOC cycle.
CREATE FUNCTION refresh_demo_vehicle_history(p_as_of timestamptz)
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
    demo_vehicle constant text := 'mock-vehicle-001';
    today_kst date := (p_as_of AT TIME ZONE 'Asia/Seoul')::date;
    seed_start date;
    anchor_date date;
    anchor_odometer double precision;
    anchor_phase integer;
    snapshots_added integer;
    completions_added integer;
BEGIN
    -- Serialize with other refreshes and the existing vehicle snapshot/event write boundary.
    PERFORM 1 FROM vehicles WHERE vehicle_id = demo_vehicle FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 0;
    END IF;

    -- The app subtracts each day's last odometer from the previous recorded day's last value.
    -- Bridge retained gaps too, or a long outage would concentrate weeks of mileage into one day.
    SELECT LEAST(today_kst - 14,
                 coalesce(min((s.recorded_at AT TIME ZONE 'Asia/Seoul')::date), today_kst - 14))
      INTO seed_start
      FROM vehicle_snapshots s
     WHERE s.vehicle_id = demo_vehicle
       AND s.recorded_at >= (today_kst - 55)::timestamp AT TIME ZONE 'Asia/Seoul'
       AND s.recorded_at < today_kst::timestamp AT TIME ZONE 'Asia/Seoul';

    -- Recover the original cycle from an owned 00:30 sample, even after older samples expire.
    SELECT (s.recorded_at AT TIME ZONE 'Asia/Seoul')::date,
           s.odometer_km, (85 - s.soc_percent) / 12
      INTO anchor_date, anchor_odometer, anchor_phase
      FROM vehicle_snapshots s
     WHERE s.vehicle_id = demo_vehicle
       AND s.recorded_at < today_kst::timestamp AT TIME ZONE 'Asia/Seoul'
       AND (s.recorded_at AT TIME ZONE 'Asia/Seoul')::time = time '00:30'
       AND s.soc_percent IN (85, 73, 61, 49)
       AND s.snapshot_id = md5('demo-driving-v4:' || demo_vehicle || ':'
           || (s.recorded_at AT TIME ZONE 'Asia/Seoul')::date::text || ':0')::uuid::text
     ORDER BY s.recorded_at
     LIMIT 1;
    IF NOT FOUND THEN
        anchor_date := today_kst - 14;
        anchor_odometer := 20000;
        anchor_phase := 0;
    END IF;

    WITH seed_days AS (
        SELECT seed_start + day_number AS record_date,
               seed_start + day_number - anchor_date AS day_offset
        FROM generate_series(0, today_kst - seed_start - 1) AS days(day_number)
    )
    INSERT INTO vehicle_snapshots
        (snapshot_id, vehicle_id, recorded_at, soc_percent, odometer_km, drivable_range_km)
    SELECT md5('demo-driving-v4:' || demo_vehicle || ':' || days.record_date::text
               || ':' || samples.sample_index::text)::uuid::text,
           demo_vehicle,
           (days.record_date + time '00:30' + samples.sample_index * interval '5 hours 45 minutes')
               AT TIME ZONE 'Asia/Seoul',
           85 - mod(mod(days.day_offset + anchor_phase, 4) + 4, 4) * 12 - samples.sample_index * 2,
           anchor_odometer + days.day_offset * 40 + samples.sample_index * 10,
           NULL
    FROM seed_days days
    CROSS JOIN generate_series(0, 4) AS samples(sample_index)
    ON CONFLICT (vehicle_id, recorded_at) DO NOTHING;
    GET DIAGNOSTICS snapshots_added = ROW_COUNT;

    -- Only generated samples can create synthetic completions. Never infer REAL visits/events.
    WITH owned_samples AS (
        SELECT s.recorded_at, s.soc_percent, s.odometer_km,
               lag(s.soc_percent) OVER (ORDER BY s.recorded_at) AS previous_soc,
               lag(s.recorded_at) OVER (ORDER BY s.recorded_at) AS previous_at
        FROM vehicle_snapshots s
        JOIN generate_series(0, 4) AS samples(sample_index)
          ON s.recorded_at = ((s.recorded_at AT TIME ZONE 'Asia/Seoul')::date
              + time '00:30' + samples.sample_index * interval '5 hours 45 minutes')
              AT TIME ZONE 'Asia/Seoul'
         AND s.snapshot_id = md5('demo-driving-v4:' || demo_vehicle || ':'
              || (s.recorded_at AT TIME ZONE 'Asia/Seoul')::date::text
              || ':' || samples.sample_index::text)::uuid::text
        WHERE s.vehicle_id = demo_vehicle
          AND s.recorded_at >= (seed_start - 1)::timestamp AT TIME ZONE 'Asia/Seoul'
          AND s.recorded_at < today_kst::timestamp AT TIME ZONE 'Asia/Seoul'
    )
    INSERT INTO charging_events
        (event_id, vehicle_id, event_type, source, verified, occurred_at,
         lat, lng, soc_percent, odometer_km, matched_station_id, visit_count_added, daily_duplicate)
    SELECT 'demo-complete:' || demo_vehicle || ':'
               || (s.recorded_at AT TIME ZONE 'Asia/Seoul')::date::text,
           demo_vehicle, 'COMPLETE', 'MOCK', false, s.recorded_at,
           37.55, 127.14, s.soc_percent, s.odometer_km, NULL, 0, false
    FROM owned_samples s
    WHERE s.soc_percent > s.previous_soc
      AND s.recorded_at = ((s.recorded_at AT TIME ZONE 'Asia/Seoul')::date + time '00:30')
          AT TIME ZONE 'Asia/Seoul'
      AND s.previous_at = ((s.recorded_at AT TIME ZONE 'Asia/Seoul')::date - 1 + time '23:30')
          AT TIME ZONE 'Asia/Seoul'
    ON CONFLICT (event_id) DO NOTHING;
    GET DIAGNOSTICS completions_added = ROW_COUNT;

    RETURN snapshots_added + completions_added;
END;
$$;

-- Make the first deployment usable before the background scheduler's first tick.
SELECT refresh_demo_vehicle_history(CURRENT_TIMESTAMP);
