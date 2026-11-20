SET LOCAL lock_timeout = '1s';

ALTER TABLE coupon_usage ADD COLUMN cancelled_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_coupon_usage_active_coupon_user ON coupon_usage (coupon_id, user_id) WHERE cancelled_at IS NULL;

ALTER TABLE coupon_usage DROP CONSTRAINT uq_coupon_usage_coupon_user;
