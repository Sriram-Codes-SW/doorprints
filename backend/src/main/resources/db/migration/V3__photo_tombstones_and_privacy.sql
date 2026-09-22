-- Wave 2 security hardening (threat model F-06, F-15, F-16). V2 belongs to the optional pgvector store.
--
-- Photos become part of offline sync: a delete leaves a tombstone (deleted = true, bytes removed) with a new
-- sync_version, so other devices learn about it via GET /api/photos?since=<cursor>. Tombstones are purged by
-- the scheduled TombstonePurgeJob after app.privacy.tombstone-retention-days (default 90).

ALTER TABLE photo ALTER COLUMN data DROP NOT NULL;
ALTER TABLE photo ADD COLUMN deleted      boolean NOT NULL DEFAULT false;
ALTER TABLE photo ADD COLUMN size_bytes   integer;
ALTER TABLE photo ADD COLUMN updated_at   timestamptz;
ALTER TABLE photo ADD COLUMN sync_version bigint;

UPDATE photo SET size_bytes = octet_length(data), updated_at = created_at, sync_version = nextval('sync_seq');

ALTER TABLE photo ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE photo ALTER COLUMN sync_version SET NOT NULL;
CREATE INDEX photo_sync_idx ON photo (sync_version);

-- Houses deleted before this migration still hold their content in the tombstone: blank it now (PRV-005).
UPDATE house
SET label = '', address = NULL, street = NULL, locality = NULL, price = NULL, price_type = NULL, bedrooms = NULL,
    rating = NULL, contact_name = NULL, contact_phone = NULL, listing_url = NULL, notes = NULL
WHERE deleted;
DELETE FROM house_checklist WHERE house_id IN (SELECT id FROM house WHERE deleted);
UPDATE photo SET deleted = true, data = NULL
WHERE house_id IN (SELECT id FROM house WHERE deleted) AND NOT deleted;

-- Soft-deleted visits: keep only what a tombstone needs (id, deleted, updated_at, sync_version); drop the place.
UPDATE visit SET street = NULL, lat = 0, lon = 0, left_at = NULL WHERE deleted;
