-- Reuse one collection_state row per region (task = 'region:' || zcode).
-- Existing task success timestamps cannot establish regional coverage: no backfill is safe.
ALTER TABLE collection_state
    ADD COLUMN sync_baseline_at timestamptz,
    ADD COLUMN last_status_sync_at timestamptz,
    ADD COLUMN status_sync_valid boolean NOT NULL DEFAULT false;

ALTER TABLE collection_state ADD CONSTRAINT collection_state_valid_sync
    CHECK (NOT status_sync_valid OR
        (sync_baseline_at IS NOT NULL AND last_status_sync_at IS NOT NULL
         AND sync_baseline_at <= last_status_sync_at));
