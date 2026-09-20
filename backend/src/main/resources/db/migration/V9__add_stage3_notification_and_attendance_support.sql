ALTER TABLE registration_cycle
    ADD COLUMN attendance_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED'
        COMMENT 'NOT_REQUIRED/PENDING/CHECKED_IN/NO_SHOW' AFTER status;

UPDATE registration_cycle c
LEFT JOIN checkin_record r ON r.registration_cycle_id = c.id AND r.status = 'VALID'
LEFT JOIN activity a ON a.id = c.activity_id
SET c.attendance_status = CASE
    WHEN c.status <> 'CONFIRMED' THEN 'NOT_REQUIRED'
    WHEN r.id IS NOT NULL THEN 'CHECKED_IN'
    WHEN a.activity_end_at <= CURRENT_TIMESTAMP(6) THEN 'NO_SHOW'
    ELSE 'PENDING'
END;

ALTER TABLE activity
    ADD KEY idx_activity_status_end_id (status, activity_end_at, id);
ALTER TABLE registration_cycle
    ADD KEY idx_cycle_activity_status_id (activity_id, status, id);
ALTER TABLE notification
    ADD KEY idx_notification_user_created_id (user_id, create_time, id);
ALTER TABLE checkin_session
    ADD KEY idx_checkin_session_activity_status_close_id
        (activity_id, status, closes_at, id);
