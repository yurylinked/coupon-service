SET LOCAL lock_timeout = '1s';

ALTER TABLE coupon ADD COLUMN expires_at TIMESTAMPTZ;

ALTER TABLE coupon ADD CONSTRAINT ck_coupon_expires_after_creation CHECK (expires_at IS NULL OR expires_at > created_at) NOT VALID;
