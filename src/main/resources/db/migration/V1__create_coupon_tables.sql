CREATE TABLE coupon (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    country_code CHAR(2) NOT NULL,
    max_uses INTEGER NOT NULL,
    current_uses INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    valid_from TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT uq_coupon_code UNIQUE (code),
    CONSTRAINT ck_coupon_code_format CHECK (code ~ '^[A-Z0-9_-]{3,32}$'),
    CONSTRAINT ck_coupon_country_format CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_coupon_max_uses CHECK (max_uses BETWEEN 1 AND 1000000),
    CONSTRAINT ck_coupon_current_uses CHECK (current_uses >= 0 AND current_uses <= max_uses),
    CONSTRAINT ck_coupon_expires_after_creation CHECK (expires_at IS NULL OR expires_at > created_at),
    CONSTRAINT ck_coupon_valid_from_before_expiry CHECK (valid_from IS NULL OR expires_at IS NULL OR valid_from < expires_at)
);

CREATE TABLE redemption (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT uuidv7(),
    coupon_id BIGINT NOT NULL REFERENCES coupon (id),
    user_id VARCHAR(64) NOT NULL,
    country_code CHAR(2) NOT NULL,
    idempotency_key VARCHAR(64),
    redeemed_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT uq_redemption_public_id UNIQUE (public_id),
    CONSTRAINT ck_redemption_user_id CHECK (user_id ~ '^[A-Za-z0-9._@:+-]{1,64}$'),
    CONSTRAINT ck_redemption_country_format CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_redemption_idempotency_key CHECK (idempotency_key IS NULL OR idempotency_key ~ '^[A-Za-z0-9._:-]{1,64}$'),
    CONSTRAINT ck_redemption_cancelled_after_redeemed CHECK (cancelled_at IS NULL OR cancelled_at >= redeemed_at)
);

CREATE UNIQUE INDEX uq_redemption_active_coupon_user ON redemption (coupon_id, user_id) WHERE cancelled_at IS NULL;

CREATE UNIQUE INDEX uq_redemption_idempotency_key ON redemption (coupon_id, user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
