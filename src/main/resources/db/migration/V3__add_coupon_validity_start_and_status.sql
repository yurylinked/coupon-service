SET LOCAL lock_timeout = '1s';

ALTER TABLE coupon ADD COLUMN valid_from TIMESTAMPTZ;

ALTER TABLE coupon ADD COLUMN active BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE coupon ADD CONSTRAINT ck_coupon_valid_from_before_expiry CHECK (valid_from IS NULL OR expires_at IS NULL OR valid_from < expires_at) NOT VALID;
