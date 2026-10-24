-- iOS pushes go straight to APNs, whose sandbox (development builds) and production hosts accept
-- different device tokens. iOS devices report which one theirs belongs to; Android rows stay NULL.
ALTER TABLE devices
    ADD COLUMN apns_environment text CHECK (apns_environment IN ('SANDBOX', 'PRODUCTION'));
