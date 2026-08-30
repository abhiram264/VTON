ALTER TABLE generation_notification_event
    ADD COLUMN reason text NULL,
    ADD CONSTRAINT generation_notification_event_reason_check
        CHECK (reason IS NULL OR reason ~ '^[A-Z0-9_]{1,64}$');
