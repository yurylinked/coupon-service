SET LOCAL lock_timeout = '1s';

ALTER TABLE coupon VALIDATE CONSTRAINT ck_coupon_expires_after_creation;

ALTER TABLE coupon VALIDATE CONSTRAINT ck_coupon_valid_from_before_expiry;
