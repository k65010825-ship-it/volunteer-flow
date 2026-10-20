-- Read-only diagnostic. Set exact IDs from the protected manifest.
-- Null/unset variables select no event or position; driver asserts counts separately.
SELECT event_id,status,retry_count,published_partition,published_offset
FROM outbox_event WHERE event_id=@stage5_event_id;
SELECT consumer_name,event_id,processed_at FROM consumed_event WHERE event_id=@stage5_event_id;
SELECT id,user_id,deduplication_key FROM notification
WHERE deduplication_key=CONCAT(@stage5_event_id,':',@stage5_user_id,':IN_APP');
SELECT position_id,status,request_version,processed_version FROM promotion_reconcile_task
WHERE position_id=@stage5_position_id;
SELECT id,registration_cycle_id,status FROM promotion_offer WHERE position_id=@stage5_position_id;
