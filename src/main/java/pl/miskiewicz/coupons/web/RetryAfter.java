package pl.miskiewicz.coupons.web;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Clients turned away by the same stall should not all come back at the same second.
@Component
class RetryAfter {

    private final long minSeconds;
    private final long maxSeconds;

    RetryAfter(
            @Value("${coupons.retry-after.min:1s}") Duration min,
            @Value("${coupons.retry-after.max:3s}") Duration max) {
        if (min.toSeconds() < 1 || max.compareTo(min) < 0) {
            throw new IllegalArgumentException("Retry-After needs 1 s <= min <= max");
        }
        this.minSeconds = min.toSeconds();
        this.maxSeconds = max.toSeconds();
    }

    String seconds() {
        return Long.toString(ThreadLocalRandom.current().nextLong(minSeconds, maxSeconds + 1));
    }
}
