package pl.miskiewicz.coupons.coupon.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("coupons.redemption.bulkhead")
public record BulkheadProperties(int permitsPerStripe, int stripes, Duration maxWait) {

    public BulkheadProperties {
        Objects.requireNonNull(maxWait, "maxWait");
        if (permitsPerStripe < 1 || stripes < 1 || maxWait.isNegative()) {
            throw new IllegalArgumentException("coupons.redemption.bulkhead needs a permit, a stripe and a non-negative wait");
        }
    }
}
