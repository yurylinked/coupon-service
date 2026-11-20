package pl.miskiewicz.coupons.web;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("coupons.retry-after")
public record RetryAfterProperties(Duration min, Duration max) {

    public RetryAfterProperties {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.toSeconds() < 1 || max.compareTo(min) < 0) {
            throw new IllegalArgumentException("coupons.retry-after needs 1 s <= min <= max");
        }
    }
}
