package pl.miskiewicz.coupons.coupon.persistence;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("coupons.redemption")
public record RedemptionLockProperties(Duration lockTimeout) {

    // PostgreSQL reads a lock_timeout of 0 as waiting forever.
    public RedemptionLockProperties {
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        if (lockTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("coupons.redemption.lock-timeout must be at least 1 ms");
        }
    }
}
