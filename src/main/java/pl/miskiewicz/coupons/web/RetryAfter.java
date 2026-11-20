package pl.miskiewicz.coupons.web;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

// Clients turned away by the same stall should not all come back at the same second.
@Component
class RetryAfter {

    private final long minSeconds;
    private final long maxSeconds;

    RetryAfter(RetryAfterProperties properties) {
        this.minSeconds = properties.min().toSeconds();
        this.maxSeconds = properties.max().toSeconds();
    }

    String seconds() {
        return Long.toString(ThreadLocalRandom.current().nextLong(minSeconds, maxSeconds + 1));
    }
}
