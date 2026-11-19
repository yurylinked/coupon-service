CREATE TABLE coupon (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    country_code CHAR(2) NOT NULL,
    max_uses INTEGER NOT NULL,
    current_uses INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_coupon_code UNIQUE (code),
    CONSTRAINT ck_coupon_code_format CHECK (code ~ '^[A-Z0-9_-]{3,32}$'),
    CONSTRAINT ck_coupon_country_format CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_coupon_max_uses CHECK (max_uses >= 1),
    CONSTRAINT ck_coupon_current_uses CHECK (current_uses >= 0 AND current_uses <= max_uses)
);

CREATE TABLE coupon_usage (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT uuidv7(),
    coupon_id BIGINT NOT NULL REFERENCES coupon (id),
    user_id VARCHAR(64) NOT NULL,
    country_code CHAR(2) NOT NULL,
    used_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_coupon_usage_public_id UNIQUE (public_id),
    CONSTRAINT uq_coupon_usage_coupon_user UNIQUE (coupon_id, user_id),
    CONSTRAINT ck_coupon_usage_user_id CHECK (char_length(user_id) BETWEEN 1 AND 64)
);
