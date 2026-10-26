-- When each account wants its daily insight. MORNING follows the server's morning time,
-- FIXED uses delivery_time, SURPRISE picks a different time inside the server's window each day.
-- delivery_time is kept when someone switches away from FIXED so the app can offer it again.
ALTER TABLE accounts
    ADD COLUMN delivery_mode text NOT NULL DEFAULT 'MORNING'
        CHECK (delivery_mode IN ('MORNING', 'FIXED', 'SURPRISE')),
    ADD COLUMN delivery_time time,
    ADD CONSTRAINT accounts_fixed_needs_time CHECK (delivery_mode <> 'FIXED' OR delivery_time IS NOT NULL),
    -- Set after a failed attempt so a broken writer is not retried on every scheduler tick.
    ADD COLUMN insight_retry_at timestamptz;
